import { t } from "../i18n/i18n";
import { ClipboardButton } from "./Clipboard";
import { NotificationBell } from "../personal/Personal";
import { InboxLink } from "../workflow/Inbox";
import { Archive, BarChart3, ClipboardList, ChevronRight, LayoutDashboard, LogOut, Settings } from "lucide-react";
import { type ReactNode } from "react";
import { Link, NavLink, Outlet, useParams } from "react-router";
import { useCanOpen, useContextById, useMe, useShows } from "../api/queries";
import { userManager } from "../auth/oidc";
import { initials } from "./common";
import { GlobalSearch } from "./GlobalSearch";
import { Navigator, useNavigatorCollapsed } from "./Navigator";

export function Layout() {
  const { data: me } = useMe();
  const shows = useShows();
  const [collapsed] = useNavigatorCollapsed();

  return (
    <div className="shell">
      <header className="topbar">
        <Link to="/" className="brand">
          <img src="/favicon.svg" alt="" />
          PLM
        </Link>
        {shows("search") && <GlobalSearch />}
        <div className="spacer" />
        <InboxLink />
        <NavLink to="/changes" className="topbar-link">
          <ClipboardList size={14} aria-hidden /> {t("Changes")}
        </NavLink>
        <NavLink to="/reports" className="topbar-link">
          <BarChart3 size={14} aria-hidden /> {t("Reports")}
        </NavLink>
        <NavLink to="/baselines" className="topbar-link">
          <Archive size={14} aria-hidden /> {t("Baselines")}
        </NavLink>
        <NavLink to="/dashboards" className="topbar-link">
          <LayoutDashboard size={14} aria-hidden /> {t("Dashboards")}
        </NavLink>
        <ClipboardButton />
        <NotificationBell />
        <div className="who">
          {me?.siteAdmin && (
            <NavLink to="/admin" className="topbar-link">
              <Settings size={14} aria-hidden />
              {t("Admin")}
            </NavLink>
          )}
          {me?.siteAdmin && <span className="chip-admin">{t("Site admin")}</span>}
          <span className="avatar" aria-hidden>
            {initials(me?.user.displayName)}
          </span>
          <Link to="/preferences" className="topbar-link" title={t("My preferences")} data-testid="current-user">
            {me?.user.displayName}
          </Link>
          <button className="icon-btn" aria-label={t("Sign out")} title={t("Sign out")} onClick={() => void userManager.signoutRedirect()}>
            <LogOut size={15} />
          </button>
        </div>
      </header>
      <div className={collapsed ? "body nav-collapsed" : "body"}>
        <Navigator />
        <main className="main">
          <Outlet />
        </main>
      </div>
    </div>
  );
}

/** Site / Organization / Product trail for a context. */
export function Breadcrumbs({ contextId, current }: { contextId: string; current?: string }) {
  return (
    <nav className="crumbs" aria-label={t("Breadcrumb")}>
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
  const canOpen = useCanOpen();
  if (!data || !canOpen) return null;
  // The site is only for site administrators: other people's trail starts at the organization.
  if (data.kind === "SITE" && !canOpen(data)) return null;
  return (
    <>
      {data.parentId && <ParentCrumb id={data.parentId} />}
      {isLast || !canOpen(data) ? <span>{data.name}</span> : <Link to={`/contexts/${data.id}`}>{data.name}</Link>}
    </>
  );
}

function ParentCrumb({ id }: { id: string }) {
  const { data } = useContextById(id);
  const canOpen = useCanOpen();
  if (!data || !canOpen || (data.kind === "SITE" && !canOpen(data))) return null;
  return (
    <>
      <CrumbChain id={id} isLast={false} />
      <ChevronRight size={11} aria-hidden />
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
export function Tabs<T extends string>({ tabs, active, onSelect }: { tabs: [T, string][]; active: T; onSelect: (tab: T) => void }) {
  return (
    <nav className="tabs" aria-label={t("Sections")}>
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
