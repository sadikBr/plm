import { useState } from "react";
import { Link, NavLink, Outlet, useParams } from "react-router";
import { useChildren, useContextById, useMe, useSite, type Context } from "../api/queries";
import { userManager } from "../auth/oidc";
import { Spinner } from "./common";

export function Layout() {
  const { data: me } = useMe();

  return (
    <div className="shell">
      <header className="topbar">
        <Link to="/" className="brand">
          <img src="/favicon.svg" alt="" />
          PLM
        </Link>
        <div className="spacer" />
        <div className="user">
          {me?.siteAdmin && <span className="badge accent">Site admin</span>}
          <span data-testid="current-user">{me?.user.displayName}</span>
          <button className="btn small" onClick={() => void userManager.signoutRedirect()}>
            Sign out
          </button>
        </div>
      </header>
      <div className="body">
        <nav className="sidebar" aria-label="Contexts">
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
  if (isLoading) return <Spinner />;
  if (!site) return <p className="muted" style={{ padding: 8 }}>You are not on any team yet.</p>;
  return (
    <ul className="tree">
      <TreeNode context={site} initiallyOpen />
    </ul>
  );
}

function TreeNode({ context, initiallyOpen = false }: { context: Context; initiallyOpen?: boolean }) {
  const [open, setOpen] = useState(initiallyOpen);
  const leaf = context.kind === "PRODUCT" || context.kind === "LIBRARY" || context.kind === "PROJECT";
  const { data: children } = useChildren(context.id, open && !leaf);

  return (
    <li>
      <div className="tree-row">
        {leaf ? (
          <span className="tree-spacer" />
        ) : (
          <button
            className="tree-toggle"
            aria-label={`${open ? "Collapse" : "Expand"} ${context.name}`}
            aria-expanded={open}
            onClick={() => setOpen(!open)}
          >
            {open ? "▼" : "▶"}
          </button>
        )}
        <NavLink to={`/contexts/${context.id}`} className={({ isActive }) => (isActive ? "active" : undefined)}>
          {context.name}
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
          <span aria-hidden>/</span>
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
          <span aria-hidden>/</span>
        </>
      )}
      {isLast ? <span>{data.name}</span> : <Link to={`/contexts/${data.id}`}>{data.name}</Link>}
    </>
  );
}

/** Used by pages that need the id from the URL. */
export function useIdParam(): string {
  const { id } = useParams();
  if (!id) throw new Error("Route is missing :id");
  return id;
}
