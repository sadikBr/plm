import { t, tn } from "../i18n/i18n";
import { ClipboardButton } from "./Clipboard";
import { NotificationBell } from "../personal/Personal";
import { useTaskCounts } from "../workflow/Inbox";
import {
  Archive,
  BarChart3,
  ChevronDown,
  ChevronRight,
  ClipboardList,
  FolderTree,
  Home,
  Inbox as InboxIcon,
  LayoutDashboard,
  LogOut,
  Settings,
  ShieldCheck,
  SlidersHorizontal,
} from "lucide-react";
import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from "react";
import { Link, NavLink, Outlet, useParams } from "react-router";
import { useCanOpen, useContextById, useMe, useShows } from "../api/queries";
import { userManager } from "../auth/oidc";
import { initials } from "./common";
import { GlobalSearch } from "./GlobalSearch";
import { Navigator, useNavigatorCollapsed } from "./Navigator";

/**
 * The application frame: a slim top bar (brand, search, clipboard, notifications, account), the
 * app rail of sections on the left, the navigator of containers, and the page.
 */
export function Layout() {
  const shows = useShows();
  const [collapsed] = useNavigatorCollapsed();

  return (
    <div className="shell">
      <header className="topbar">
        <Link to="/" className="brand" aria-label={t("PLM home")}>
          <img src="/favicon.svg" alt="" />
          <span>PLM</span>
        </Link>
        <div className="topbar-center">{shows("search") && <GlobalSearch />}</div>
        <div className="topbar-actions">
          <ClipboardButton />
          <NotificationBell />
          <AccountMenu />
        </div>
      </header>
      <div className={collapsed ? "body nav-collapsed" : "body"}>
        <AppRail />
        <Navigator />
        <main className="main">
          <Outlet />
        </main>
      </div>
    </div>
  );
}

/** The sections of the application, one icon and a short name each; Administration at the foot. */
function AppRail() {
  const { data: me } = useMe();
  const { data: counts } = useTaskCounts();
  const open = counts?.open ?? 0;
  const item = (to: string, icon: ReactNode, label: string, extra?: { aria?: string; badge?: ReactNode; end?: boolean }) => (
    <NavLink to={to} end={extra?.end} className="rail-item" title={label} aria-label={extra?.aria}>
      <span className="rail-icon">
        {icon}
        {extra?.badge}
      </span>
      <span className="rail-label">{label}</span>
    </NavLink>
  );
  return (
    <nav className="rail" aria-label={t("Main menu")}>
      {item("/", <Home size={18} aria-hidden />, t("Home"), { end: true })}
      {item("/inbox", <InboxIcon size={18} aria-hidden />, t("Tasks"), {
        aria: tn("Tasks: {n} open", "Tasks: {n} open", open),
        badge: open > 0 ? <span className={counts?.overdue ? "rail-badge overdue" : "rail-badge"}>{open > 99 ? "99+" : open}</span> : null,
      })}
      {item("/changes", <ClipboardList size={18} aria-hidden />, t("Changes"))}
      {item("/classes", <FolderTree size={18} aria-hidden />, t("Classes"))}
      {item("/reports", <BarChart3 size={18} aria-hidden />, t("Reports"))}
      {item("/baselines", <Archive size={18} aria-hidden />, t("Baselines"))}
      {item("/dashboards", <LayoutDashboard size={18} aria-hidden />, t("Dashboards"))}
      <span className="rail-grow" />
      {me?.siteAdmin && item("/admin", <Settings size={18} aria-hidden />, t("Admin"))}
    </nav>
  );
}

/** Who is signed in, with their preferences and sign-out a click away. */
function AccountMenu() {
  const { data: me } = useMe();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => ref.current && !ref.current.contains(e.target as Node) && setOpen(false);
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    document.addEventListener("mousedown", onDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);
  return (
    <div className="menu-wrap account" ref={ref}>
      <button
        className="account-btn"
        aria-haspopup="menu"
        aria-expanded={open}
        data-testid="current-user"
        title={t("Your account")}
        onClick={() => setOpen(!open)}
      >
        <span className="avatar" aria-hidden>
          {initials(me?.user.displayName)}
        </span>
        <span className="account-name">{me?.user.displayName}</span>
        {me?.siteAdmin && <span className="chip-admin">{t("Site admin")}</span>}
        <ChevronDown size={13} aria-hidden />
      </button>
      {open && (
        <div className="menu account-menu" role="menu">
          <div className="account-card">
            <span className="avatar large" aria-hidden>
              {initials(me?.user.displayName)}
            </span>
            <div>
              <strong>{me?.user.displayName}</strong>
              <div className="muted small">{me?.user.email ?? me?.user.username}</div>
              {me?.siteAdmin && (
                <div className="account-role">
                  <ShieldCheck size={12} aria-hidden /> {t("Site administrator")}
                </div>
              )}
            </div>
          </div>
          <hr />
          <Link to="/preferences" role="menuitem" className="menu-link" onClick={() => setOpen(false)}>
            <SlidersHorizontal size={14} aria-hidden /> {t("My preferences")}
          </Link>
          <button role="menuitem" onClick={() => void userManager.signoutRedirect()}>
            <LogOut size={14} aria-hidden /> {t("Sign out")}
          </button>
        </div>
      )}
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
  variant,
}: {
  /** "object": a larger header for an object page, its number above its name. */
  variant?: "object";
  crumbs?: ReactNode;
  icon?: ReactNode;
  title: ReactNode;
  meta?: ReactNode;
  toolbar?: ReactNode;
  tabs?: ReactNode;
}) {
  return (
    <div className={variant ? `page-head ${variant}-head` : "page-head"}>
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

/**
 * Tab strip driven by the `tab` search parameter. Tabs that don't fit move into a "More" menu (the
 * active one always stays in view); without a ResizeObserver (tests), every tab shows.
 */
export function Tabs<T extends string>({ tabs, active, onSelect }: { tabs: [T, string][]; active: T; onSelect: (tab: T) => void }) {
  const strip = useRef<HTMLElement>(null);
  const ruler = useRef<HTMLDivElement>(null);
  const [fit, setFit] = useState(tabs.length);
  const [moreOpen, setMoreOpen] = useState(false);
  const moreRef = useRef<HTMLDivElement>(null);
  const keys = tabs.map(([k]) => k).join("|");

  useLayoutEffect(() => {
    const el = strip.current;
    if (!el || typeof ResizeObserver === "undefined") return;
    // Measured on the hidden ruler, which always holds every tab at its natural width.
    const ro = new ResizeObserver(() => {
      const widths = [...(ruler.current?.children ?? [])].map((c) => (c as HTMLElement).offsetWidth);
      const room = el.clientWidth - 28;
      if (widths.reduce((a, b) => a + b, 0) <= room) return setFit(widths.length);
      const more = 96;
      let used = 0;
      let n = 0;
      while (n < widths.length && used + widths[n]! + more <= room) used += widths[n++]!;
      setFit(Math.max(1, n));
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, [keys]);

  useEffect(() => {
    if (!moreOpen) return;
    const onDown = (e: MouseEvent) => moreRef.current && !moreRef.current.contains(e.target as Node) && setMoreOpen(false);
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setMoreOpen(false);
    document.addEventListener("mousedown", onDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [moreOpen]);

  const activeIndex = tabs.findIndex(([k]) => k === active);
  let shown = tabs.slice(0, fit);
  // The active tab stays in view: it takes the last visible place.
  if (fit < tabs.length && activeIndex >= fit) shown = [...tabs.slice(0, Math.max(0, fit - 1)), tabs[activeIndex]!];
  const hidden = tabs.filter((tab) => !shown.includes(tab));
  const link = (key: T, label: string, inMenu = false) => (
    <a
      key={key}
      href={`?tab=${key}`}
      className={active === key ? "active" : undefined}
      aria-current={active === key ? "page" : undefined}
      onClick={(e) => {
        e.preventDefault();
        if (inMenu) setMoreOpen(false);
        onSelect(key);
      }}
    >
      {label}
    </a>
  );

  return (
    <nav className="tabs" aria-label={t("Sections")} ref={strip}>
      {shown.map(([key, label]) => link(key, label))}
      {hidden.length > 0 && (
        <div className="tabs-more" ref={moreRef}>
          <button
            type="button"
            className="tabs-more-btn"
            aria-expanded={moreOpen}
            aria-haspopup="true"
            onClick={() => setMoreOpen(!moreOpen)}
          >
            {t("More")}
            <span className="tabs-more-count">{hidden.length}</span>
            <ChevronDown size={13} aria-hidden />
          </button>
          <div className="tabs-more-menu" hidden={!moreOpen}>
            {hidden.map(([key, label]) => link(key, label, true))}
          </div>
        </div>
      )}
      <div className="tabs-ruler" ref={ruler} aria-hidden>
        {tabs.map(([key, label]) => (
          <span key={key}>{label}</span>
        ))}
      </div>
    </nav>
  );
}

/** Used by pages that need the id from the URL. */
export function useIdParam(): string {
  const { id } = useParams();
  if (!id) throw new Error("Route is missing :id");
  return id;
}
