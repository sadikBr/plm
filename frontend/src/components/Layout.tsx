import { ChevronDown, ChevronRight, LogOut, Settings } from "lucide-react";
import { useState, type ReactNode } from "react";
import { Link, NavLink, Outlet, useParams } from "react-router";
import { HOLDS_OBJECTS, useChildren, useContextById, useMe, useSite, type Context } from "../api/queries";
import { userManager } from "../auth/oidc";
import { ContextIcon, initials, Spinner } from "./common";
import { GlobalSearch } from "./GlobalSearch";

export function Layout() {
  const { data: me } = useMe();

  return (
    <div className="shell">
      <header className="topbar">
        <Link to="/" className="brand">
          <img src="/favicon.svg" alt="" />
          PLM
        </Link>
        <GlobalSearch />
        <div className="spacer" />
        <div className="who">
          {me?.siteAdmin && (
            <NavLink to="/admin" className="topbar-link">
              <Settings size={14} aria-hidden />
              Admin
            </NavLink>
          )}
          {me?.siteAdmin && <span className="chip-admin">Site admin</span>}
          <span className="avatar" aria-hidden>
            {initials(me?.user.displayName)}
          </span>
          <Link to="/preferences" className="topbar-link" title="My preferences" data-testid="current-user">
            {me?.user.displayName}
          </Link>
          <button
            className="icon-btn"
            aria-label="Sign out"
            title="Sign out"
            onClick={() => void userManager.signoutRedirect()}
          >
            <LogOut size={15} />
          </button>
        </div>
      </header>
      <div className="body">
        <nav className="sidebar" aria-label="Contexts">
          <div className="sidebar-title">Navigator</div>
          <ContextTree />
        </nav>
        <main className="main">
          <Outlet />
        </main>
      </div>
    </div>
  );
}

/** Lazily expanding tree of the contexts the user can see. */
function ContextTree() {
  const { data: site, isLoading } = useSite();
  if (isLoading) return <div style={{ padding: 12 }}><Spinner /></div>;
  if (!site) return <p className="muted" style={{ padding: "4px 12px" }}>You are not on any team yet.</p>;
  return (
    <ul className="tree">
      <TreeNode context={site} initiallyOpen />
    </ul>
  );
}

function TreeNode({ context, initiallyOpen = false }: { context: Context; initiallyOpen?: boolean }) {
  const [open, setOpen] = useState(initiallyOpen);
  const leaf = HOLDS_OBJECTS.has(context.kind);
  const { data: children } = useChildren(context.id, open && !leaf);
  const { id: currentId } = useParams();

  return (
    <li>
      <div className={currentId === context.id ? "tree-row active" : "tree-row"}>
        {leaf ? (
          <span className="tree-spacer" />
        ) : (
          <button
            className="tree-toggle"
            aria-label={`${open ? "Collapse" : "Expand"} ${context.name}`}
            aria-expanded={open}
            onClick={() => setOpen(!open)}
          >
            {open ? <ChevronDown size={13} /> : <ChevronRight size={13} />}
          </button>
        )}
        <NavLink to={`/contexts/${context.id}`}>
          <ContextIcon kind={context.kind} size={14} />
          <span>{context.name}</span>
        </NavLink>
      </div>
      {open && children && children.length > 0 && (
        <ul>
          {children.map((child) => (
            <TreeNode key={child.id} context={child} />
          ))}
        </ul>
      )}
    </li>
  );
}

/** Site / Organization / Product trail for a context. */
export function Breadcrumbs({ contextId, current }: { contextId: string; current?: string }) {
  return (
    <nav className="crumbs" aria-label="Breadcrumb">
      <CrumbChain id={contextId} isLast={!current} />
      {current && (
        <>
          <ChevronRight size={11} aria-hidden />
          <span>{current}</span>
        </>
      )}
    </nav>
  );
}

function CrumbChain({ id, isLast }: { id: string; isLast: boolean }) {
  const { data } = useContextById(id);
  if (!data) return null;
  return (
    <>
      {data.parentId && (
        <>
          <CrumbChain id={data.parentId} isLast={false} />
          <ChevronRight size={11} aria-hidden />
        </>
      )}
      {isLast ? <span>{data.name}</span> : <Link to={`/contexts/${data.id}`}>{data.name}</Link>}
    </>
  );
}

/**
 * The header strip every object and context page shares: breadcrumbs, title with icon and
 * status chips, a toolbar of actions, then tabs.
 */
export function PageHeader({
  crumbs,
  icon,
  title,
  meta,
  toolbar,
  tabs,
}: {
  crumbs?: ReactNode;
  icon?: ReactNode;
  title: ReactNode;
  meta?: ReactNode;
  toolbar?: ReactNode;
  tabs?: ReactNode;
}) {
  return (
    <div className="page-head">
      {crumbs ?? <div className="crumbs" />}
      <div className="title-row">
        <h1>
          {icon}
          {title}
        </h1>
        {meta && <div className="meta">{meta}</div>}
      </div>
      {toolbar && <div className="toolbar">{toolbar}</div>}
      {tabs}
    </div>
  );
}

/** Tab strip driven by the `tab` search parameter. */
export function Tabs<T extends string>({
  tabs,
  active,
  onSelect,
}: {
  tabs: [T, string][];
  active: T;
  onSelect: (tab: T) => void;
}) {
  return (
    <nav className="tabs" aria-label="Sections">
      {tabs.map(([key, label]) => (
        <a
          key={key}
          href={`?tab=${key}`}
          className={active === key ? "active" : undefined}
          aria-current={active === key ? "page" : undefined}
          onClick={(e) => {
            e.preventDefault();
            onSelect(key);
          }}
        >
          {label}
        </a>
      ))}
    </nav>
  );
}

/** Used by pages that need the id from the URL. */
export function useIdParam(): string {
  const { id } = useParams();
  if (!id) throw new Error("Route is missing :id");
  return id;
}
