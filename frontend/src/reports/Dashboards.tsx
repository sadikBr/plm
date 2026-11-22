import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  AlarmClock,
  ArrowDown,
  ArrowRight,
  ArrowUp,
  Bell,
  BellOff,
  ClipboardList,
  Eye,
  History,
  Home,
  Inbox as InboxIcon,
  LayoutDashboard,
  Lock,
  Pencil,
  Plus,
  Star,
  Trash2,
  X,
} from "lucide-react";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { api, unwrap, type Schemas } from "../api/client";
import { useMe } from "../api/queries";
import {
  Dialog,
  EmptyState,
  ErrorBanner,
  Loading,
  MenuButton,
  Panel,
  StateBadge,
  TypeIcon,
  formatAgo,
  formatDate,
} from "../components/common";
import { PageHeader } from "../components/Layout";
import { BarChart } from "./BarChart";
import { useBars, useReportRun } from "./ReportsPage";
import { QueryTable } from "./ReportBuilder";
import { favoriteLink, useFavorites, useNotifications, useRecentVisits, useUnreadCount } from "../personal/Personal";
import { useTaskCounts } from "../workflow/Inbox";
import { locale, t, tn } from "../i18n/i18n";
import { MyChanges, OpenByState, useChangeMetrics } from "../changes/ChangeDashboard";

type Dashboard = Schemas["Dashboard"];
type Widget = Schemas["Widget"];
type WidgetKind = Widget["kind"];

const KINDS = (): [WidgetKind, string, boolean][] => [
  ["REPORT_CHART", t("A report's chart"), true],
  ["REPORT_COUNT", t("A report's total"), true],
  ["REPORT_TABLE", t("A report's first rows"), true],
  ["CHECKED_OUT", t("What I have checked out"), false],
  ["CHANGES_OPEN", t("Open changes by state"), false],
  ["CHANGES_MINE", t("My open changes"), false],
  ["RECENT", t("Recently changed"), false],
  ["RECENT_VISITS", t("What I viewed lately"), false],
  ["FAVORITES", t("My favorites"), false],
  ["NOTIFICATIONS", t("My notifications"), false],
];
const DEFAULT_HOME_ID = "00000000-0000-0000-0000-000000000000";

const useReports = () => useQuery({ queryKey: ["reports"], queryFn: () => unwrap(api.GET("/api/v1/reports")) });

/** One widget's content (roadmap 3.8). */
function WidgetBody({ widget }: { widget: Widget }) {
  const report = useReportRun(widget.kind.startsWith("REPORT") ? (widget.reportId ?? undefined) : undefined);
  const bars = useBars(report.data);
  const { data: checkedOut } = useQuery({
    queryKey: ["home", "checkedOut", widget.limit],
    queryFn: () => unwrap(api.GET("/api/v1/home/checked-out", { params: { query: { limit: widget.limit } } })),
    enabled: widget.kind === "CHECKED_OUT",
  });
  const { data: recent } = useQuery({
    queryKey: ["home", "recent", widget.limit],
    queryFn: () => unwrap(api.GET("/api/v1/home/recent", { params: { query: { limit: widget.limit } } })),
    enabled: widget.kind === "RECENT",
  });
  if (widget.kind === "CHANGES_OPEN" || widget.kind === "CHANGES_MINE") return <ChangesWidget kind={widget.kind} limit={widget.limit} />;
  if (widget.kind === "RECENT_VISITS") return <VisitsWidget limit={widget.limit} />;
  if (widget.kind === "FAVORITES") return <FavoritesWidget limit={widget.limit} />;
  if (widget.kind === "NOTIFICATIONS") return <NoticesWidget limit={widget.limit} />;
  if (widget.kind.startsWith("REPORT")) {
    if (report.error) return <ErrorBanner error={report.error} />;
    if (!report.data) return <Loading />;
    if (widget.kind === "REPORT_COUNT")
      return (
        <div className="stat-tile">
          <span className="value">
            {report.data.total}
            {report.data.capped ? "+" : ""}
          </span>
          <span className="muted small">{t("found · {date}", { date: formatDate(report.data.ranAt) })}</span>
        </div>
      );
    if (widget.kind === "REPORT_CHART") return <BarChart bars={bars} />;
    if (report.data.table) return <QueryTable result={report.data.table} label={t("Report results")} limit={widget.limit} />;
    return <MiniList items={report.data.rows.slice(0, widget.limit)} />;
  }
  const items = widget.kind === "CHECKED_OUT" ? checkedOut : recent;
  if (!items) return <Loading />;
  if (!items.length)
    return widget.kind === "CHECKED_OUT" ? (
      <EmptyState icon={<Lock size={18} />} title={t("Nothing checked out.")} hint={t("Check out a part or document to edit it.")} />
    ) : (
      <EmptyState icon={<History size={18} />} title={t("Nothing changed lately.")} />
    );
  return <MiniList items={items as Item[]} when={widget.kind === "RECENT" ? "modifiedAt" : undefined} />;
}

function VisitsWidget({ limit }: { limit: number }) {
  const { data } = useRecentVisits(limit);
  if (!data) return <Loading />;
  if (!data.length) return <EmptyState icon={<Eye size={18} />} title={t("Nothing viewed yet.")} />;
  return <MiniList items={data.map((v) => ({ ...v, id: v.objectId }))} when="visitedAt" />;
}

function FavoritesWidget({ limit }: { limit: number }) {
  const { data } = useFavorites();
  if (!data) return <Loading />;
  if (!data.length)
    return <EmptyState icon={<Star size={18} />} title={t("No favorites yet: use the star on an object, product or report.")} />;
  return (
    <ul className="mini-list">
      {data.slice(0, limit).map((f) => (
        <li key={`${f.kind}:${f.targetId}`}>
          <Star size={14} className="icon-muted" aria-hidden />
          <Link to={favoriteLink(f)} className="grow ellipsis">
            {f.label}
          </Link>
          <span className="muted small">{f.kind === "OBJECT" ? t("Object") : f.kind === "REPORT" ? t("Report") : t("Context")}</span>
        </li>
      ))}
    </ul>
  );
}

function NoticesWidget({ limit }: { limit: number }) {
  const { data } = useNotifications(false, limit);
  if (!data) return <Loading />;
  if (!data.length) return <EmptyState icon={<BellOff size={18} />} title={t("No notifications.")} />;
  return (
    <ul className="mini-list">
      {data.map((n) => (
        <li key={n.id} className={n.readAt ? undefined : "unread"}>
          {n.link ? (
            <Link to={n.link} className="grow ellipsis">
              {n.title}
            </Link>
          ) : (
            <span className="grow ellipsis">{n.title}</span>
          )}
          <span className="muted small nowrap" title={formatDate(n.createdAt)}>
            {formatAgo(n.createdAt)}
          </span>
        </li>
      ))}
    </ul>
  );
}

type Item = {
  id: string;
  number: string;
  name: string;
  typeId: string;
  version: string;
  state: string;
  modifiedAt?: string;
  visitedAt?: string;
};

const FIRST = 6;

/** Objects, two lines each: number and name, then version, state and when. Long lists show their first rows. */
function MiniList({ items, when }: { items: Item[]; when?: "modifiedAt" | "visitedAt" }) {
  const [all, setAll] = useState(false);
  const shown = all ? items : items.slice(0, FIRST);
  return (
    <>
      <ul className="mini-list object-list">
        {shown.map((o) => (
          <li key={o.id}>
            <span className="object-list-icon">
              <TypeIcon typeId={o.typeId} />
            </span>
            <span className="object-list-main">
              <Link to={`/objects/${o.id}`} className="object-list-title">
                <span className="num">{o.number}</span> {o.name}
              </Link>
              <span className="object-list-meta">
                <span className="version">{o.version}</span>
                <StateBadge state={o.state} />
              </span>
            </span>
            {when && o[when] && (
              <span className="object-list-when" title={formatDate(o[when])}>
                {formatAgo(o[when])}
              </span>
            )}
          </li>
        ))}
      </ul>
      {items.length > FIRST && (
        <button className="mini-list-more" onClick={() => setAll(!all)}>
          {all ? t("Show fewer") : tn("Show {n} more", "Show {n} more", items.length - FIRST)}
        </button>
      )}
    </>
  );
}

/** A dashboard's widgets on a two-column grid; its owner can edit it in place. */
export function DashboardView({ dashboard, editable }: { dashboard: Dashboard; editable: boolean }) {
  const qc = useQueryClient();
  const { data: reports } = useReports();
  const [editing, setEditing] = useState(false);
  const [widgets, setWidgets] = useState<Widget[]>(dashboard.widgets);
  const [adding, setAdding] = useState(false);
  const save = useMutation({
    mutationFn: () =>
      unwrap(
        api.PUT("/api/v1/dashboards/{id}", {
          params: { path: { id: dashboard.id } },
          body: { name: dashboard.name, shared: dashboard.shared, widgets },
        }),
      ),
    onSuccess: () => {
      setEditing(false);
      void qc.invalidateQueries({ queryKey: ["dashboards"] });
      void qc.invalidateQueries({ queryKey: ["home"] });
    },
  });
  const shown = editing ? widgets : dashboard.widgets;
  const titleOf = (w: Widget) =>
    w.title ||
    (w.reportId ? (reports?.find((r) => r.id === w.reportId)?.settings.name ?? t("Report")) : KINDS().find(([k]) => k === w.kind)?.[1]);
  const move = (i: number, d: number) => {
    const next = [...widgets];
    const [w] = next.splice(i, 1);
    next.splice(i + d, 0, w!);
    setWidgets(next);
  };

  return (
    <div className="dashboard">
      {editable && (
        <div className="row" style={{ gap: 6, justifyContent: "flex-end" }}>
          {editing ? (
            <>
              <ErrorBanner error={save.error} />
              <button className="btn small" onClick={() => setAdding(true)}>
                <Plus size={13} aria-hidden /> {t("Add a widget")}
              </button>
              <button className="btn small" onClick={() => (setEditing(false), setWidgets(dashboard.widgets))}>
                {t("Cancel")}
              </button>
              <button className="btn small primary" disabled={save.isPending} onClick={() => save.mutate()}>
                {t("Save layout")}
              </button>
            </>
          ) : (
            <button className="btn small" onClick={() => (setWidgets(dashboard.widgets), setEditing(true))}>
              <Pencil size={13} aria-hidden /> {t("Edit layout")}
            </button>
          )}
        </div>
      )}
      <Masonry
        items={shown.map((w, i) => ({
          key: w.id,
          wide: w.width === 2,
          node: (
            <Panel
              title={titleOf(w) ?? ""}
              flush
              className="widget"
              actions={
                editing ? (
                  <span className="row" style={{ gap: 2 }}>
                    <button className="icon-btn" aria-label={t("Move up")} disabled={i === 0} onClick={() => move(i, -1)}>
                      <ArrowUp size={12} />
                    </button>
                    <button className="icon-btn" aria-label={t("Move down")} disabled={i === widgets.length - 1} onClick={() => move(i, 1)}>
                      <ArrowDown size={12} />
                    </button>
                    <button
                      className="btn small"
                      onClick={() => setWidgets(widgets.map((x, j) => (j === i ? { ...x, width: x.width === 2 ? 1 : 2 } : x)))}
                    >
                      {w.width === 2 ? t("Half width") : t("Full width")}
                    </button>
                    <button
                      className="icon-btn"
                      aria-label={t("Remove the widget")}
                      onClick={() => setWidgets(widgets.filter((_, j) => j !== i))}
                    >
                      <X size={13} />
                    </button>
                  </span>
                ) : w.reportId ? (
                  <Link className="small" to={`/reports/${w.reportId}`}>
                    {t("Open report")}
                  </Link>
                ) : undefined
              }
            >
              <WidgetBody widget={w} />
            </Panel>
          ),
        }))}
      />
      {!shown.length && <EmptyState icon={<LayoutDashboard size={18} />} title={t("No widgets yet.")} />}
      {adding && <AddWidgetDialog onClose={() => setAdding(false)} onAdd={(w) => setWidgets([...widgets, w])} />}
    </div>
  );
}

function AddWidgetDialog({ onClose, onAdd }: { onClose: () => void; onAdd: (w: Widget) => void }) {
  const { data: reports } = useReports();
  const [kind, setKind] = useState<WidgetKind>("REPORT_CHART");
  const [reportId, setReportId] = useState("");
  const [title, setTitle] = useState("");
  const [width, setWidth] = useState(1);
  const needsReport = KINDS().find(([k]) => k === kind)?.[2];
  return (
    <Dialog
      open
      title={t("Add a widget")}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            {t("Cancel")}
          </button>
          <button
            className="btn primary"
            disabled={needsReport && !reportId}
            onClick={() => {
              onAdd({ id: crypto.randomUUID(), kind, reportId: needsReport ? reportId : null, title: title || null, width, limit: 10 });
              onClose();
            }}
          >
            {t("Add")}
          </button>
        </>
      }
    >
      <div className="attr-form">
        <div className="field wide">
          <label htmlFor="w-kind">{t("Shows")}</label>
          <select id="w-kind" value={kind} onChange={(e) => setKind(e.target.value as WidgetKind)}>
            {KINDS().map(([k, l]) => (
              <option key={k} value={k}>
                {l}
              </option>
            ))}
          </select>
        </div>
        {needsReport && (
          <div className="field wide">
            <label htmlFor="w-report">{t("Report")}</label>
            <select id="w-report" value={reportId} onChange={(e) => setReportId(e.target.value)}>
              <option value="">{t("Choose…")}</option>
              {reports?.map((r) => (
                <option key={r.id} value={r.id}>
                  {r.settings.name}
                </option>
              ))}
            </select>
          </div>
        )}
        <div className="field">
          <label htmlFor="w-title">{t("Title")}</label>
          <input id="w-title" value={title} onChange={(e) => setTitle(e.target.value)} placeholder={t("Optional")} />
        </div>
        <div className="field">
          <label htmlFor="w-width">{t("Width")}</label>
          <select id="w-width" value={width} onChange={(e) => setWidth(Number(e.target.value))}>
            <option value={1}>{t("Half")}</option>
            <option value={2}>{t("Full")}</option>
          </select>
        </div>
      </div>
    </Dialog>
  );
}

/**
 * Widgets in two columns that fill from the top, each half-width widget going under the
 * shorter column so tall and short ones leave no holes; full-width widgets span both.
 * In a narrow space it is one column in the widgets' order.
 */
function Masonry({ items }: { items: { key: string; wide: boolean; node: ReactNode }[] }) {
  const rows: { key: string; wide: boolean; node: ReactNode; order: number }[][] = [];
  items.forEach((it, order) => {
    const last = rows[rows.length - 1];
    if (it.wide || !last || last[0]!.wide) rows.push([{ ...it, order }]);
    else last.push({ ...it, order });
  });
  return (
    <div className="masonry">
      {rows.map((row) =>
        row[0]!.wide ? (
          <div key={row[0]!.key} className="masonry-wide" style={{ order: row[0]!.order }}>
            {row[0]!.node}
          </div>
        ) : (
          <BalancedColumns key={row[0]!.key} items={row} />
        ),
      )}
    </div>
  );
}

/** Puts each widget under the shorter of two columns, measuring them as they render. */
function BalancedColumns({ items }: { items: { key: string; node: ReactNode; order: number }[] }) {
  const refs = useRef(new Map<string, HTMLDivElement>());
  const [heights, setHeights] = useState<Record<string, number>>({});
  useEffect(() => {
    if (typeof ResizeObserver === "undefined") return;
    const ro = new ResizeObserver(() => {
      const next: Record<string, number> = {};
      refs.current.forEach((el, k) => (next[k] = el.offsetHeight));
      setHeights((h) => (Object.keys(next).every((k) => Math.abs((h[k] ?? -1) - next[k]!) < 2) ? h : next));
    });
    refs.current.forEach((el) => ro.observe(el));
    return () => ro.disconnect();
  }, [items.length]);
  const cols: (typeof items)[] = [[], []];
  const sum = [0, 0];
  items.forEach((it, i) => {
    const h = heights[it.key];
    const c = h === undefined ? i % 2 : sum[0]! <= sum[1]! ? 0 : 1;
    cols[c]!.push(it);
    sum[c]! += h ?? 0;
  });
  return (
    <div className="masonry-cols">
      {cols.map((col, c) => (
        <div key={c} className="masonry-col">
          {col.map((it) => (
            <div
              key={it.key}
              style={{ order: it.order }}
              ref={(el) => {
                if (el) refs.current.set(it.key, el);
                else refs.current.delete(it.key);
              }}
            >
              {it.node}
            </div>
          ))}
        </div>
      ))}
    </div>
  );
}

/** Choose a home page among the dashboards you can see, or make your own from the default. */
function HomeChooser({ current }: { current: Dashboard }) {
  const qc = useQueryClient();
  const { data: dashboards } = useQuery({ queryKey: ["dashboards"], queryFn: () => unwrap(api.GET("/api/v1/dashboards")) });
  const set = useMutation({
    mutationFn: (dashboardId: string | null) => unwrap(api.PUT("/api/v1/home", { body: { dashboardId } })),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["home"] }),
  });
  const customize = useMutation({
    mutationFn: async () => {
      const d = await unwrap(api.POST("/api/v1/dashboards", { body: { name: t("My home"), widgets: current.widgets, shared: false } }));
      return unwrap(api.PUT("/api/v1/home", { body: { dashboardId: d.id } }));
    },
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["home"] });
      void qc.invalidateQueries({ queryKey: ["dashboards"] });
    },
  });
  return (
    <>
      {current.id === DEFAULT_HOME_ID && (
        <button className="btn ghost" onClick={() => customize.mutate()} disabled={customize.isPending}>
          <Pencil size={14} aria-hidden /> {t("Arrange my home page")}
        </button>
      )}
      <MenuButton label={t("Home page")} icon={<Home size={14} aria-hidden />}>
        {(close) => (
          <>
            <button role="menuitem" onClick={() => (set.mutate(null), close())}>
              {t("The default")}
            </button>
            {dashboards?.map((d) => (
              <button key={d.id} role="menuitem" onClick={() => (set.mutate(d.id), close())}>
                {d.name} {d.id === current.id ? "✓" : ""}
              </button>
            ))}
          </>
        )}
      </MenuButton>
    </>
  );
}

const greeting = (hour: number, name: string) =>
  hour < 5 || hour >= 18
    ? t("Good evening, {name}", { name })
    : hour < 12
      ? t("Good morning, {name}", { name })
      : t("Good afternoon, {name}", { name });

/** Your home page: a greeting with what waits for you, then your chosen dashboard, or the default one. */
export function HomeDashboard() {
  const { data: me } = useMe();
  const { data: home, error } = useQuery({ queryKey: ["home"], queryFn: () => unwrap(api.GET("/api/v1/home")) });
  const first = me?.user.displayName.split(" ")[0] ?? "";
  const now = new Date();
  return (
    <div className="home">
      <section className="home-hero">
        <div className="home-hero-text">
          <p className="home-date">{now.toLocaleDateString(locale(), { weekday: "long", day: "numeric", month: "long" })}</p>
          <h1>{me ? greeting(now.getHours(), first) : t("Home")}</h1>
          <HomeSummary />
        </div>
        <div className="home-hero-tools">
          {home && home.id !== DEFAULT_HOME_ID && <span className="home-board">{home.name}</span>}
          {home && <HomeChooser current={home} />}
        </div>
      </section>
      <HomeStats />
      <div className="home-board-body">
        <ErrorBanner error={error} />
        {home ? <DashboardView key={home.id + home.updatedAt} dashboard={home} editable={home.owner === me?.user.id} /> : <Loading />}
      </div>
    </div>
  );
}

const useCheckedOut = () =>
  useQuery({
    queryKey: ["home", "checkedOut", 100],
    queryFn: () => unwrap(api.GET("/api/v1/home/checked-out", { params: { query: { limit: 100 } } })),
  });

/** One sentence: what needs you today. */
function HomeSummary() {
  const { data: tasks } = useTaskCounts();
  const { data: unread } = useUnreadCount();
  const { data: out } = useCheckedOut();
  if (!tasks || !unread || !out) return <p className="home-summary">&nbsp;</p>;
  const parts: string[] = [];
  if (tasks.open) parts.push(tn("{n} open task", "{n} open tasks", tasks.open));
  if (tasks.overdue) parts.push(tn("{n} of them overdue", "{n} of them overdue", tasks.overdue));
  if (unread.unread) parts.push(tn("{n} unread notification", "{n} unread notifications", unread.unread));
  if (out.length) parts.push(tn("{n} object checked out", "{n} objects checked out", out.length));
  return (
    <p className="home-summary">
      {parts.length ? t("You have {list}.", { list: parts.join(", ") }) : t("You're all caught up: nothing waits for you.")}
    </p>
  );
}

/** The numbers that matter, each a way in. */
function HomeStats() {
  const { data: tasks } = useTaskCounts();
  const { data: unread } = useUnreadCount();
  const { data: changes } = useChangeMetrics();
  const card = (to: string, icon: ReactNode, tone: string, value: number | undefined, label: string, hint?: string) => (
    <Link to={to} className={`home-stat tone-${tone}`}>
      <span className="home-stat-icon">{icon}</span>
      <span className="home-stat-text">
        <span className="home-stat-value">{value ?? "–"}</span>
        <span className="home-stat-label">{label}</span>
        {hint && <span className="home-stat-hint">{hint}</span>}
      </span>
      <ArrowRight size={15} className="home-stat-go" aria-hidden />
    </Link>
  );
  return (
    <nav className="home-stats" aria-label={t("At a glance")}>
      {card("/inbox", <InboxIcon size={18} aria-hidden />, "blue", tasks?.open, t("Open tasks"), t("Assigned to you or your roles"))}
      {card(
        "/inbox?show=overdue",
        <AlarmClock size={18} aria-hidden />,
        tasks?.overdue ? "red" : "green",
        tasks?.overdue,
        t("Overdue tasks"),
        tasks?.overdue ? t("Past their due date") : t("All on time"),
      )}
      {card(
        "/changes",
        <ClipboardList size={18} aria-hidden />,
        "violet",
        changes?.open,
        t("Open changes"),
        t("Problems, requests and notices"),
      )}
      {card("/notifications", <Bell size={18} aria-hidden />, "amber", unread?.unread, t("Unread notifications"), t("On what you follow"))}
    </nav>
  );
}

/** Dashboards: list, and one opened. */
export function DashboardsPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { data: me } = useMe();
  const { data: dashboards, isLoading } = useQuery({ queryKey: ["dashboards"], queryFn: () => unwrap(api.GET("/api/v1/dashboards")) });
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState("");
  const [shared, setShared] = useState(true);
  const create = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/dashboards", { body: { name, shared, widgets: [] } })),
    onSuccess: (d) => {
      void qc.invalidateQueries({ queryKey: ["dashboards"] });
      setCreating(false);
      navigate(`/dashboards/${d.id}`);
    },
  });
  const remove = useMutation({
    mutationFn: (d: string) => unwrap(api.DELETE("/api/v1/dashboards/{id}", { params: { path: { id: d } } })),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["dashboards"] });
      navigate("/dashboards");
    },
  });
  const current = dashboards?.find((d) => d.id === id) ?? (id ? undefined : dashboards?.[0]);
  return (
    <>
      <PageHeader
        icon={<LayoutDashboard size={18} className="icon-muted" aria-hidden />}
        title={<span className="nm">{current ? current.name : t("Dashboards")}</span>}
        toolbar={
          <>
            <MenuButton label={t("Open")} align="left">
              {(close) => (
                <>
                  {dashboards?.map((d) => (
                    <button key={d.id} role="menuitem" onClick={() => (navigate(`/dashboards/${d.id}`), close())}>
                      {d.name}
                    </button>
                  ))}
                </>
              )}
            </MenuButton>
            <button className="btn ghost" onClick={() => setCreating(true)}>
              <Plus size={15} aria-hidden /> {t("New dashboard")}
            </button>
            {current?.owner === me?.user.id && current && (
              <button
                className="btn ghost"
                onClick={() => window.confirm(t("Delete “{name}”?", { name: current.name })) && remove.mutate(current.id)}
              >
                <Trash2 size={14} aria-hidden /> {t("Delete")}
              </button>
            )}
          </>
        }
      />
      <div className="content">
        {isLoading ? (
          <Loading />
        ) : current ? (
          <DashboardView key={current.id + current.updatedAt} dashboard={current} editable={current.owner === me?.user.id} />
        ) : (
          <p className="empty">{t("No dashboards yet.")}</p>
        )}
      </div>
      {creating && (
        <Dialog
          open
          title={t("New dashboard")}
          onClose={() => setCreating(false)}
          footer={
            <>
              <button className="btn" onClick={() => setCreating(false)}>
                {t("Cancel")}
              </button>
              <button className="btn primary" disabled={!name.trim() || create.isPending} onClick={() => create.mutate()}>
                {t("Create")}
              </button>
            </>
          }
        >
          <div className="attr-form">
            <ErrorBanner error={create.error} />
            <div className="field wide">
              <label htmlFor="dash-name">{t("Name")}</label>
              <input id="dash-name" value={name} onChange={(e) => setName(e.target.value)} autoFocus />
            </div>
            <label className="check wide">
              <input type="checkbox" checked={shared} onChange={(e) => setShared(e.target.checked)} />
              {t("Share it: anyone can open it or make it their home page")}
            </label>
          </div>
        </Dialog>
      )}
    </>
  );
}

/** Change widgets (roadmap 5.12). */
function ChangesWidget({ kind, limit }: { kind: "CHANGES_OPEN" | "CHANGES_MINE"; limit: number }) {
  const { data, error } = useChangeMetrics();
  if (error) return <ErrorBanner error={error} />;
  if (!data) return <Loading />;
  if (kind === "CHANGES_MINE") return <MyChanges mine={data.mine.slice(0, limit)} compact />;
  return (
    <>
      <OpenByState metrics={data} />
      <p className="muted small" style={{ padding: "0 14px 10px" }}>
        <Link to="/changes?view=dashboard">{t("{n} open · change dashboard", { n: data.open })}</Link>
      </p>
    </>
  );
}
