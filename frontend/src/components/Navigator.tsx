import { useQuery } from "@tanstack/react-query";
import { BarChart3, Box, ChevronDown, ChevronRight, ChevronsLeft, ChevronsRight, Clock, Pin, PinOff, Search, Star, X } from "lucide-react";
import { favoriteLink, useFavorites } from "../personal/Personal";
import { NavSharedWithMe } from "../collaboration/Collaboration";
import { useEffect, useState, useSyncExternalStore, type ReactNode } from "react";
import { Link, NavLink, useLocation } from "react-router";
import { api, unwrap } from "../api/client";
import {
  useBrowsableContexts,
  useCanOpen,
  useContextById,
  useManagedOrganizations,
  useMe,
  useObject,
  useOrganizationNames,
  useSite,
  type Context,
  type ContextKind,
  useShows,
} from "../api/queries";
import { BROWSABLE_KINDS, ContextIcon, Spinner, browsePath, kindFromSlug, kindLabel, kindPlural } from "./common";

// ---------- Per-viewer memory: pinned and recent contexts, collapsed state ----------
// Conveniences only, so they live in this browser. Storage can be unavailable (private mode):
// every access is guarded and the navigator works without it.

interface Remembered {
  id: string;
  name: string;
  kind: ContextKind;
  /** Missing in entries saved before it was remembered. */
  parentId?: string | null;
}

const listeners = new Set<() => void>();
const cache = new Map<string, unknown>();

function read<T>(key: string, fallback: T): T {
  if (!cache.has(key)) {
    try {
      const raw = localStorage.getItem(key);
      cache.set(key, raw ? (JSON.parse(raw) as T) : fallback);
    } catch {
      cache.set(key, fallback);
    }
  }
  return cache.get(key) as T;
}

function write<T>(key: string, value: T) {
  cache.set(key, value);
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {
    // Not persisted; still works for this session.
  }
  listeners.forEach((l) => l());
}

function useStored<T>(key: string, fallback: T): T {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
    () => read(key, fallback),
  );
}

const PINNED = "plm.nav.pinned";
const RECENT = "plm.nav.recent";
const COLLAPSED = "plm.nav.collapsed";
const OPEN_KINDS = "plm.nav.openKinds";
const NONE: Remembered[] = [];

/** Remembers a visit, most recent first; called by the context page. */
export function rememberVisit(context: Context) {
  const entry = { id: context.id, name: context.name, kind: context.kind, parentId: context.parentId };
  const recent = read<Remembered[]>(RECENT, NONE).filter((r) => r.id !== context.id);
  write(RECENT, [entry, ...recent].slice(0, 6));
  // Keep pinned names up to date after a rename.
  const pinned = read<Remembered[]>(PINNED, NONE);
  if (pinned.some((p) => p.id === context.id && (p.name !== context.name || p.parentId !== context.parentId))) {
    write(
      PINNED,
      pinned.map((p) => (p.id === context.id ? entry : p)),
    );
  }
}

function togglePin(context: Remembered) {
  const pinned = read<Remembered[]>(PINNED, NONE);
  write(PINNED, pinned.some((p) => p.id === context.id) ? pinned.filter((p) => p.id !== context.id) : [...pinned, context]);
}

export function useNavigatorCollapsed(): [boolean, (v: boolean) => void] {
  return [useStored(COLLAPSED, false), (v) => write(COLLAPSED, v)];
}

// ---------- Where we are ----------

/** The context the current page is about (the context itself, or the object's), and the kind being browsed. */
function useWhere(): { contextId?: string; kind?: ContextKind } {
  const { pathname } = useLocation();
  const [, section, id] = pathname.split("/");
  const { data: obj } = useObject(section === "objects" && id ? id : "");
  const contextId = section === "contexts" ? id : section === "objects" ? obj?.contextId : undefined;
  const { data: context } = useContextById(contextId ?? "");
  if (section === "browse") return { kind: kindFromSlug(id) };
  return { contextId, kind: context?.kind };
}

// ---------- The navigator ----------

const MAX_LISTED = 12;
const NO_KINDS: ContextKind[] = [];

export function Navigator() {
  const [collapsed, setCollapsed] = useNavigatorCollapsed();
  const [filter, setFilter] = useState("");
  const { contextId, kind } = useWhere();
  const { data: siteContext } = useSite();
  const canOpen = useCanOpen();
  const managed = useManagedOrganizations();
  // The site and organizations are for administrators; others see the containers they work in.
  const site = siteContext && canOpen?.(siteContext) ? siteContext : undefined;
  const { data: me } = useMe();
  const shows = useShows();
  const kinds = BROWSABLE_KINDS.filter((k) => k !== "ORGANIZATION" || !!me?.siteAdmin || !!managed?.length).filter((k) =>
    shows(`browse.${kindPlural(k).toLowerCase()}`),
  );
  const allowed = (r: Remembered) => !!canOpen?.(r);
  const pinned = useStored<Remembered[]>(PINNED, NONE).filter(allowed);
  const recent = useStored<Remembered[]>(RECENT, NONE)
    .filter((r) => allowed(r) && !pinned.some((p) => p.id === r.id))
    .slice(0, 5);

  if (collapsed) {
    return (
      <nav className="sidebar collapsed" aria-label="Navigator">
        <button className="nav-icon-btn" aria-label="Expand navigator" title="Expand navigator" onClick={() => setCollapsed(false)}>
          <ChevronsRight size={15} />
        </button>
        {site && (
          <NavLink to={`/contexts/${site.id}`} className="nav-rail-item" title={site.name} aria-label={site.name} end>
            <ContextIcon kind="SITE" size={15} />
          </NavLink>
        )}
        {kinds.map((k) => (
          <NavLink
            key={k}
            to={browsePath(k)}
            className={k === kind ? "nav-rail-item active" : "nav-rail-item"}
            title={kindPlural(k)}
            aria-label={kindPlural(k)}
          >
            <ContextIcon kind={k} size={15} />
          </NavLink>
        ))}
        {pinned.length > 0 && <span className="nav-rail-sep" aria-hidden />}
        {pinned.map((p) => (
          <NavLink key={p.id} to={`/contexts/${p.id}`} className="nav-rail-item" title={p.name} aria-label={p.name}>
            <ContextIcon kind={p.kind} size={15} />
          </NavLink>
        ))}
      </nav>
    );
  }

  return (
    <nav className="sidebar" aria-label="Navigator">
      <div className="nav-head">
        <span className="sidebar-title">Navigator</span>
        <button className="nav-icon-btn" aria-label="Collapse navigator" title="Collapse navigator" onClick={() => setCollapsed(true)}>
          <ChevronsLeft size={15} />
        </button>
      </div>
      <div className="nav-filter">
        <Search size={13} aria-hidden />
        <input
          aria-label="Filter contexts"
          placeholder="Find a product, library…"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          onKeyDown={(e) => e.key === "Escape" && setFilter("")}
        />
        {filter && (
          <button className="nav-clear" aria-label="Clear filter" onClick={() => setFilter("")}>
            <X size={12} />
          </button>
        )}
      </div>

      {filter.trim() ? (
        <FilterResults text={filter.trim()} currentId={contextId} />
      ) : (
        <>
          <NavSection title="Browse" grow>
            <ul className="nav-scroll nav-kinds" aria-label="Browse by kind">
              {site && (
                <li>
                  <div className={contextId === site.id ? "nav-row kind-row active" : "nav-row kind-row"}>
                    <span className="tree-spacer" />
                    <NavLink to={`/contexts/${site.id}`} title={site.name} end>
                      <span className="kind kind-site">
                        <ContextIcon kind="SITE" size={14} />
                      </span>
                      <span className="nav-name">Site</span>
                    </NavLink>
                  </div>
                </li>
              )}
              {kinds.map((k) => (
                <KindRow key={k} kind={k} active={k === kind} browsing={k === kind && !contextId} currentId={contextId} pinned={pinned} />
              ))}
            </ul>
          </NavSection>
          <NavFavorites />
          <NavSharedWithMe
            section={(title, icon, children) => (
              <NavSection title={title} icon={icon}>
                {children}
              </NavSection>
            )}
          />
          {pinned.length > 0 && (
            <NavSection title="Pinned" icon={<Pin size={11} aria-hidden />}>
              {pinned.map((p) => (
                <FlatRow key={p.id} item={p} current={p.id === contextId} pinned />
              ))}
            </NavSection>
          )}
          {recent.length > 0 && (
            <NavSection title="Recent" icon={<Clock size={11} aria-hidden />}>
              {recent.map((r) => (
                <FlatRow key={r.id} item={r} current={r.id === contextId} pinned={false} />
              ))}
            </NavSection>
          )}
        </>
      )}
    </nav>
  );
}

/** One kind (Products, Libraries, ...): a link to its list, and the visible contexts of that kind under it. */
function KindRow({
  kind,
  active,
  browsing,
  currentId,
  pinned,
}: {
  kind: ContextKind;
  active: boolean;
  browsing: boolean;
  currentId?: string;
  pinned: Remembered[];
}) {
  const openKinds = useStored<ContextKind[]>(OPEN_KINDS, NO_KINDS);
  // The kind we're in opens itself; the others open on request (and stay open).
  const open = active || openKinds.includes(kind);
  const { data: contexts, isLoading } = useBrowsableContexts(open ? kind : undefined);
  const pinnedIds = new Set(pinned.map((p) => p.id));
  const toggle = () => write(OPEN_KINDS, openKinds.includes(kind) ? openKinds.filter((k) => k !== kind) : [...openKinds, kind]);
  // The first few, plus where we are if it's further down.
  const shown = contexts?.filter((c, i) => i < MAX_LISTED || c.id === currentId) ?? [];

  return (
    <li>
      <div className={browsing ? "nav-row kind-row active" : active ? "nav-row kind-row on-path" : "nav-row kind-row"}>
        <button
          className="tree-toggle"
          aria-label={`${open ? "Hide" : "Show"} ${kindPlural(kind).toLowerCase()}`}
          aria-expanded={open}
          onClick={toggle}
          disabled={active}
        >
          {open ? <ChevronDown size={13} /> : <ChevronRight size={13} />}
        </button>
        <NavLink to={browsePath(kind)}>
          <span className={`kind kind-${kind.toLowerCase()}`}>
            <ContextIcon kind={kind} size={14} />
          </span>
          <span className="nav-name">{kindPlural(kind)}</span>
          {contexts && <span className="nav-hint">{contexts.length}</span>}
        </NavLink>
      </div>
      {open && (
        <ul className="nav-kind-items" aria-label={kindPlural(kind)}>
          {isLoading && (
            <li className="nav-none">
              <Spinner />
            </li>
          )}
          {contexts && contexts.length === 0 && <li className="nav-none">None you can see</li>}
          {shown.map((c) => (
            <li key={c.id}>
              <FlatRow item={c} current={c.id === currentId} pinned={pinnedIds.has(c.id)} indent />
            </li>
          ))}
          {contexts && contexts.length > MAX_LISTED && (
            <li>
              <Link className="nav-more" to={browsePath(kind)}>
                All {contexts.length} {kindPlural(kind).toLowerCase()}
              </Link>
            </li>
          )}
        </ul>
      )}
    </li>
  );
}

function NavSection({ title, icon, children, grow = false }: { title: string; icon?: ReactNode; children: ReactNode; grow?: boolean }) {
  return (
    <section className={grow ? "nav-section grow" : "nav-section"} aria-label={title}>
      <div className="nav-section-title">
        {icon}
        {title}
      </div>
      {children}
    </section>
  );
}

function PinButton({ item, pinned }: { item: Remembered; pinned: boolean }) {
  return (
    <button
      className={pinned ? "nav-pin on" : "nav-pin"}
      aria-label={pinned ? `Unpin ${item.name}` : `Pin ${item.name}`}
      title={pinned ? "Unpin" : "Pin to the top"}
      onClick={(e) => {
        e.preventDefault();
        togglePin(item);
      }}
    >
      {pinned ? <PinOff size={12} /> : <Pin size={12} />}
    </button>
  );
}

function FlatRow({ item, current, pinned, indent = false }: { item: Remembered; current: boolean; pinned: boolean; indent?: boolean }) {
  const organizations = useOrganizationNames();
  const org = item.parentId ? organizations?.get(item.parentId) : undefined;
  return (
    <div className={current ? "nav-row active" : "nav-row"} style={indent ? { paddingLeft: 22 } : undefined}>
      <NavLink
        to={`/contexts/${item.id}`}
        title={`${item.name} · ${kindLabel(item.kind)}${org ? ` in ${org}` : ""}`}
        aria-current={current ? "page" : undefined}
      >
        <span className={`kind kind-${item.kind.toLowerCase()}`}>
          <ContextIcon kind={item.kind} size={14} />
        </span>
        <span className="nav-name">{item.name}</span>
        {org && <span className="nav-org">{org}</span>}
      </NavLink>
      <PinButton item={item} pinned={pinned} />
    </div>
  );
}

function FilterResults({ text, currentId }: { text: string; currentId?: string }) {
  // A short pause, so typing doesn't send a request per key.
  const [query, setQuery] = useState(text);
  useEffect(() => {
    const t = setTimeout(() => setQuery(text), 180);
    return () => clearTimeout(t);
  }, [text]);
  const { data, isFetching } = useQuery({
    queryKey: ["contextSearch", query],
    queryFn: () =>
      unwrap(
        api.GET("/api/v1/contexts", {
          params: { query: { q: query, limit: 30 } },
        }),
      ),
  });
  const pinned = new Set(read<Remembered[]>(PINNED, NONE).map((p) => p.id));
  const canOpen = useCanOpen();
  if (!data || !canOpen) return <div className="nav-empty">{isFetching || !canOpen ? <Spinner /> : null}</div>;
  const matches = data.filter(canOpen);
  if (matches.length === 0) return <p className="nav-empty">No context matches “{query}”.</p>;
  return (
    <NavSection title={`${matches.length} match${matches.length === 1 ? "" : "es"}`} grow>
      <div className="nav-scroll">
        {matches.map((c) => (
          <FlatRow key={c.id} item={c} current={c.id === currentId} pinned={pinned.has(c.id)} />
        ))}
      </div>
    </NavSection>
  );
}

/** The user's favorites (roadmap 3.9), kept on the server so they follow them to any browser. */
function NavFavorites() {
  const { data } = useFavorites();
  const { pathname } = useLocation();
  if (!data?.length) return null;
  return (
    <NavSection title="Favorites" icon={<Star size={11} aria-hidden />}>
      <ul className="nav-favorites" aria-label="Favorites">
        {data.slice(0, 10).map((f) => {
          const to = favoriteLink(f);
          return (
            <li key={`${f.kind}:${f.targetId}`}>
              <div className={pathname === to ? "nav-row active" : "nav-row"}>
                <NavLink to={to} title={f.label}>
                  <span className="kind">
                    {f.kind === "OBJECT" ? <Box size={14} /> : f.kind === "REPORT" ? <BarChart3 size={14} /> : <Star size={14} />}
                  </span>
                  <span className="nav-name">{f.label}</span>
                </NavLink>
              </div>
            </li>
          );
        })}
      </ul>
    </NavSection>
  );
}
