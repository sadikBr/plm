import { useQuery } from "@tanstack/react-query";
import { ChevronDown, ChevronRight, ChevronsLeft, ChevronsRight, Clock, Pin, PinOff, Search, X } from "lucide-react";
import { useEffect, useState, useSyncExternalStore, type ReactNode } from "react";
import { NavLink, useLocation } from "react-router";
import { api, unwrap } from "../api/client";
import { HOLDS_OBJECTS, useChildren, useObject, useSite, type Context, type ContextKind } from "../api/queries";
import { ContextIcon, Spinner, kindLabel } from "./common";

// ---------- Per-viewer memory: pinned and recent contexts, collapsed state ----------
// Conveniences only, so they live in this browser. Storage can be unavailable (private mode):
// every access is guarded and the navigator works without it.

interface Remembered {
  id: string;
  name: string;
  kind: ContextKind;
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
const NONE: Remembered[] = [];

/** Remembers a visit, most recent first; called by the context page. */
export function rememberVisit(context: Context) {
  const entry = { id: context.id, name: context.name, kind: context.kind };
  const recent = read<Remembered[]>(RECENT, NONE).filter((r) => r.id !== context.id);
  write(RECENT, [entry, ...recent].slice(0, 6));
  // Keep pinned names up to date after a rename.
  const pinned = read<Remembered[]>(PINNED, NONE);
  if (pinned.some((p) => p.id === context.id && p.name !== context.name)) {
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

/** The context the current page is about: the context itself, or the object's context. */
function useCurrentContextId(): string | undefined {
  const { pathname } = useLocation();
  const [, section, id] = pathname.split("/");
  const { data: obj } = useObject(section === "objects" && id ? id : "");
  if (section === "contexts") return id;
  if (section === "objects") return obj?.contextId;
  return undefined;
}

/** Ids from the site down to [contextId]: those tree nodes open themselves. */
function usePathIds(contextId: string | undefined) {
  const { data } = useQuery({
    queryKey: ["contextPath", contextId ?? ""],
    queryFn: () => unwrap(api.GET("/api/v1/contexts/{id}/path", { params: { path: { id: contextId! } } })),
    enabled: !!contextId,
    staleTime: 60_000,
  });
  return new Set((data ?? []).map((c) => c.id));
}

// ---------- The navigator ----------

export function Navigator() {
  const [collapsed, setCollapsed] = useNavigatorCollapsed();
  const [filter, setFilter] = useState("");
  const currentId = useCurrentContextId();
  const path = usePathIds(currentId);
  const pinned = useStored<Remembered[]>(PINNED, NONE);
  const recent = useStored<Remembered[]>(RECENT, NONE).filter((r) => !pinned.some((p) => p.id === r.id)).slice(0, 5);

  if (collapsed) {
    return (
      <nav className="sidebar collapsed" aria-label="Contexts">
        <button className="nav-icon-btn" aria-label="Expand navigator" title="Expand navigator" onClick={() => setCollapsed(false)}>
          <ChevronsRight size={15} />
        </button>
        {pinned.map((p) => (
          <NavLink key={p.id} to={`/contexts/${p.id}`} className="nav-rail-item" title={p.name} aria-label={p.name}>
            <ContextIcon kind={p.kind} size={15} />
          </NavLink>
        ))}
      </nav>
    );
  }

  return (
    <nav className="sidebar" aria-label="Contexts">
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
        <FilterResults text={filter.trim()} currentId={currentId} />
      ) : (
        <>
          {pinned.length > 0 && (
            <NavSection title="Pinned" icon={<Pin size={11} aria-hidden />}>
              {pinned.map((p) => (
                <FlatRow key={p.id} item={p} current={p.id === currentId} pinned />
              ))}
            </NavSection>
          )}
          {recent.length > 0 && (
            <NavSection title="Recent" icon={<Clock size={11} aria-hidden />}>
              {recent.map((r) => (
                <FlatRow key={r.id} item={r} current={r.id === currentId} pinned={false} />
              ))}
            </NavSection>
          )}
          <NavSection title="All contexts" grow>
            <ContextTree path={path} currentId={currentId} pinnedIds={new Set(pinned.map((p) => p.id))} />
          </NavSection>
        </>
      )}
    </nav>
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

function FlatRow({ item, current, pinned }: { item: Remembered; current: boolean; pinned: boolean }) {
  return (
    <div className={current ? "nav-row active" : "nav-row"}>
      <NavLink to={`/contexts/${item.id}`} title={`${item.name} · ${kindLabel(item.kind)}`}>
        <span className={`kind kind-${item.kind.toLowerCase()}`}>
          <ContextIcon kind={item.kind} size={14} />
        </span>
        <span className="nav-name">{item.name}</span>
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
    queryFn: () => unwrap(api.GET("/api/v1/contexts", { params: { query: { q: query, limit: 30 } } })),
  });
  const pinned = new Set(read<Remembered[]>(PINNED, NONE).map((p) => p.id));
  if (!data) return <div className="nav-empty">{isFetching ? <Spinner /> : null}</div>;
  if (data.length === 0) return <p className="nav-empty">No context matches “{query}”.</p>;
  return (
    <NavSection title={`${data.length} match${data.length === 1 ? "" : "es"}`} grow>
      <div className="nav-scroll">
        {data.map((c) => (
          <FlatRow key={c.id} item={c} current={c.id === currentId} pinned={pinned.has(c.id)} />
        ))}
      </div>
    </NavSection>
  );
}

/** Lazily expanding tree of the contexts the user can see; opens itself along the current path. */
function ContextTree({ path, currentId, pinnedIds }: { path: Set<string>; currentId?: string; pinnedIds: Set<string> }) {
  const { data: site, isLoading } = useSite();
  if (isLoading) {
    return (
      <div className="nav-empty">
        <Spinner />
      </div>
    );
  }
  if (!site) return <p className="nav-empty">You are not on any team yet.</p>;
  return (
    <ul className="tree nav-scroll" role="tree" aria-label="All contexts">
      <TreeNode context={site} depth={0} path={path} currentId={currentId} pinnedIds={pinnedIds} />
    </ul>
  );
}

function TreeNode({
  context,
  depth,
  path,
  currentId,
  pinnedIds,
}: {
  context: Context;
  depth: number;
  path: Set<string>;
  currentId?: string;
  pinnedIds: Set<string>;
}) {
  const leaf = HOLDS_OBJECTS.has(context.kind);
  // Open by default for the site, and along the path to where we are; the user's toggle wins.
  const [toggled, setToggled] = useState<boolean | null>(null);
  const open = toggled ?? (depth === 0 || (path.has(context.id) && context.id !== currentId));
  const { data: children } = useChildren(context.id, open && !leaf);
  const current = context.id === currentId;

  return (
    <li role="treeitem" aria-expanded={leaf ? undefined : open} aria-selected={current}>
      <div className={current ? "nav-row active" : path.has(context.id) ? "nav-row on-path" : "nav-row"} style={{ paddingLeft: 4 + depth * 14 }}>
        {leaf ? (
          <span className="tree-spacer" />
        ) : (
          <button
            className="tree-toggle"
            aria-label={`${open ? "Collapse" : "Expand"} ${context.name}`}
            aria-expanded={open}
            onClick={() => setToggled(!open)}
          >
            {open ? <ChevronDown size={13} /> : <ChevronRight size={13} />}
          </button>
        )}
        <NavLink to={`/contexts/${context.id}`} title={`${context.name} · ${kindLabel(context.kind)}`}>
          <span className={`kind kind-${context.kind.toLowerCase()}`}>
            <ContextIcon kind={context.kind} size={14} />
          </span>
          <span className="nav-name">{context.name}</span>
        </NavLink>
        {context.kind !== "SITE" && <PinButton item={context} pinned={pinnedIds.has(context.id)} />}
      </div>
      {open && children && children.length > 0 && (
        <ul role="group" className="tree-children" style={{ ["--guide" as string]: `${13 + depth * 14}px` }}>
          {children.map((child) => (
            <TreeNode key={child.id} context={child} depth={depth + 1} path={path} currentId={currentId} pinnedIds={pinnedIds} />
          ))}
        </ul>
      )}
      {open && children && children.length === 0 && !leaf && (
        <div className="nav-none" style={{ paddingLeft: 26 + (depth + 1) * 14 }}>
          Nothing here yet
        </div>
      )}
    </li>
  );
}
