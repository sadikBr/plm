import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowDown, ArrowUp, Home, LayoutDashboard, Pencil, Plus, Star, Trash2, X } from "lucide-react";
import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { api, unwrap, type Schemas } from "../api/client";
import { useMe } from "../api/queries";
import { Dialog, ErrorBanner, Loading, MenuButton, Panel, StateBadge, TypeIcon, formatDate } from "../components/common";
import { PageHeader } from "../components/Layout";
import { BarChart } from "./BarChart";
import { useBars, useReportRun } from "./ReportsPage";
import { favoriteLink, useFavorites, useNotifications, useRecentVisits } from "../personal/Personal";

type Dashboard = Schemas["Dashboard"];
type Widget = Schemas["Widget"];
type WidgetKind = Widget["kind"];

const KINDS: [WidgetKind, string, boolean][] = [
  ["REPORT_CHART", "A report's chart", true],
  ["REPORT_COUNT", "A report's total", true],
  ["REPORT_TABLE", "A report's first rows", true],
  ["CHECKED_OUT", "What I have checked out", false],
  ["RECENT", "Recently changed", false],
  ["RECENT_VISITS", "What I viewed lately", false],
  ["FAVORITES", "My favorites", false],
  ["NOTIFICATIONS", "My notifications", false],
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
          <span className="muted small">found · {formatDate(report.data.ranAt)}</span>
        </div>
      );
    if (widget.kind === "REPORT_CHART") return <BarChart bars={bars} />;
    return <MiniList items={report.data.rows.slice(0, widget.limit)} />;
  }
  const items = widget.kind === "CHECKED_OUT" ? checkedOut : recent;
  if (!items) return <Loading />;
  if (!items.length) return <p className="empty">{widget.kind === "CHECKED_OUT" ? "Nothing checked out." : "Nothing changed lately."}</p>;
  return <MiniList items={items as Item[]} />;
}

function VisitsWidget({ limit }: { limit: number }) {
  const { data } = useRecentVisits(limit);
  if (!data) return <Loading />;
  if (!data.length) return <p className="empty">Nothing viewed yet.</p>;
  return <MiniList items={data.map((v) => ({ ...v, id: v.objectId }))} />;
}

function FavoritesWidget({ limit }: { limit: number }) {
  const { data } = useFavorites();
  if (!data) return <Loading />;
  if (!data.length) return <p className="empty">No favorites yet: use the star on an object, product or report.</p>;
  return (
    <ul className="mini-list">
      {data.slice(0, limit).map((f) => (
        <li key={`${f.kind}:${f.targetId}`}>
          <Star size={14} className="icon-muted" aria-hidden />
          <Link to={favoriteLink(f)} className="grow ellipsis">
            {f.label}
          </Link>
          <span className="muted small">{f.kind === "OBJECT" ? "Object" : f.kind === "REPORT" ? "Report" : "Context"}</span>
        </li>
      ))}
    </ul>
  );
}

function NoticesWidget({ limit }: { limit: number }) {
  const { data } = useNotifications(false, limit);
  if (!data) return <Loading />;
  if (!data.length) return <p className="empty">No notifications.</p>;
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
          <span className="muted small nowrap">{formatDate(n.createdAt)}</span>
        </li>
      ))}
    </ul>
  );
}

type Item = { id: string; number: string; name: string; typeId: string; version: string; state: string; modifiedAt?: string };

function MiniList({ items }: { items: Item[] }) {
  return (
    <ul className="mini-list">
      {items.map((o) => (
        <li key={o.id}>
          <TypeIcon typeId={o.typeId} />
          <Link to={`/objects/${o.id}`} className="num-link">
            {o.number}
          </Link>
          <span className="grow ellipsis">{o.name}</span>
          <span className="version">{o.version}</span>
          <StateBadge state={o.state} />
        </li>
      ))}
    </ul>
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
    (w.reportId ? (reports?.find((r) => r.id === w.reportId)?.settings.name ?? "Report") : KINDS.find(([k]) => k === w.kind)?.[1]);
  const move = (i: number, d: number) => {
    const next = [...widgets];
    const [w] = next.splice(i, 1);
    next.splice(i + d, 0, w!);
    setWidgets(next);
  };

  return (
    <div style={{ display: "grid", gap: 12 }}>
      {editable && (
        <div className="row" style={{ gap: 6, justifyContent: "flex-end" }}>
          {editing ? (
            <>
              <ErrorBanner error={save.error} />
              <button className="btn small" onClick={() => setAdding(true)}>
                <Plus size={13} aria-hidden /> Add a widget
              </button>
              <button className="btn small" onClick={() => (setEditing(false), setWidgets(dashboard.widgets))}>
                Cancel
              </button>
              <button className="btn small primary" disabled={save.isPending} onClick={() => save.mutate()}>
                Save layout
              </button>
            </>
          ) : (
            <button className="btn small" onClick={() => (setWidgets(dashboard.widgets), setEditing(true))}>
              <Pencil size={13} aria-hidden /> Edit layout
            </button>
          )}
        </div>
      )}
      <div className="dashboard-grid">
        {shown.map((w, i) => (
          <Panel
            key={w.id}
            title={titleOf(w) ?? ""}
            flush
            className={w.width === 2 ? "span-2" : undefined}
            actions={
              editing ? (
                <span className="row" style={{ gap: 2 }}>
                  <button className="icon-btn" aria-label="Move up" disabled={i === 0} onClick={() => move(i, -1)}>
                    <ArrowUp size={12} />
                  </button>
                  <button className="icon-btn" aria-label="Move down" disabled={i === widgets.length - 1} onClick={() => move(i, 1)}>
                    <ArrowDown size={12} />
                  </button>
                  <button
                    className="btn small"
                    onClick={() => setWidgets(widgets.map((x, j) => (j === i ? { ...x, width: x.width === 2 ? 1 : 2 } : x)))}
                  >
                    {w.width === 2 ? "Half width" : "Full width"}
                  </button>
                  <button className="icon-btn" aria-label="Remove the widget" onClick={() => setWidgets(widgets.filter((_, j) => j !== i))}>
                    <X size={13} />
                  </button>
                </span>
              ) : w.reportId ? (
                <Link className="small" to={`/reports/${w.reportId}`}>
                  Open report
                </Link>
              ) : undefined
            }
          >
            <WidgetBody widget={w} />
          </Panel>
        ))}
        {!shown.length && <p className="empty">No widgets yet.</p>}
      </div>
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
  const needsReport = KINDS.find(([k]) => k === kind)?.[2];
  return (
    <Dialog
      open
      title="Add a widget"
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button
            className="btn primary"
            disabled={needsReport && !reportId}
            onClick={() => {
              onAdd({ id: crypto.randomUUID(), kind, reportId: needsReport ? reportId : null, title: title || null, width, limit: 10 });
              onClose();
            }}
          >
            Add
          </button>
        </>
      }
    >
      <div className="attr-form">
        <div className="field wide">
          <label htmlFor="w-kind">Shows</label>
          <select id="w-kind" value={kind} onChange={(e) => setKind(e.target.value as WidgetKind)}>
            {KINDS.map(([k, l]) => (
              <option key={k} value={k}>
                {l}
              </option>
            ))}
          </select>
        </div>
        {needsReport && (
          <div className="field wide">
            <label htmlFor="w-report">Report</label>
            <select id="w-report" value={reportId} onChange={(e) => setReportId(e.target.value)}>
              <option value="">Choose…</option>
              {reports?.map((r) => (
                <option key={r.id} value={r.id}>
                  {r.settings.name}
                </option>
              ))}
            </select>
          </div>
        )}
        <div className="field">
          <label htmlFor="w-title">Title</label>
          <input id="w-title" value={title} onChange={(e) => setTitle(e.target.value)} placeholder="Optional" />
        </div>
        <div className="field">
          <label htmlFor="w-width">Width</label>
          <select id="w-width" value={width} onChange={(e) => setWidth(Number(e.target.value))}>
            <option value={1}>Half</option>
            <option value={2}>Full</option>
          </select>
        </div>
      </div>
    </Dialog>
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
      const d = await unwrap(api.POST("/api/v1/dashboards", { body: { name: "My home", widgets: current.widgets, shared: false } }));
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
          <Pencil size={14} aria-hidden /> Arrange my home page
        </button>
      )}
      <MenuButton label="Home page" icon={<Home size={14} aria-hidden />}>
        {(close) => (
          <>
            <button role="menuitem" onClick={() => (set.mutate(null), close())}>
              The default
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

/** Your home page: your chosen dashboard, or the default one. */
export function HomeDashboard() {
  const { data: me } = useMe();
  const { data: home, error } = useQuery({ queryKey: ["home"], queryFn: () => unwrap(api.GET("/api/v1/home")) });
  return (
    <>
      <PageHeader
        icon={<Home size={18} className="icon-muted" aria-hidden />}
        title={<span className="nm">{me ? `Welcome, ${me.user.displayName}` : "Home"}</span>}
        meta={home && home.id !== DEFAULT_HOME_ID ? <span className="sub">{home.name}</span> : undefined}
        toolbar={home ? <HomeChooser current={home} /> : undefined}
      />
      <div className="content">
        <ErrorBanner error={error} />
        {home ? <DashboardView key={home.id + home.updatedAt} dashboard={home} editable={home.owner === me?.user.id} /> : <Loading />}
      </div>
    </>
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
        title={<span className="nm">{current ? current.name : "Dashboards"}</span>}
        toolbar={
          <>
            <MenuButton label="Open" align="left">
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
              <Plus size={15} aria-hidden /> New dashboard
            </button>
            {current?.owner === me?.user.id && current && (
              <button className="btn ghost" onClick={() => window.confirm(`Delete “${current.name}”?`) && remove.mutate(current.id)}>
                <Trash2 size={14} aria-hidden /> Delete
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
          <p className="empty">No dashboards yet.</p>
        )}
      </div>
      {creating && (
        <Dialog
          open
          title="New dashboard"
          onClose={() => setCreating(false)}
          footer={
            <>
              <button className="btn" onClick={() => setCreating(false)}>
                Cancel
              </button>
              <button className="btn primary" disabled={!name.trim() || create.isPending} onClick={() => create.mutate()}>
                Create
              </button>
            </>
          }
        >
          <div className="attr-form">
            <ErrorBanner error={create.error} />
            <div className="field wide">
              <label htmlFor="dash-name">Name</label>
              <input id="dash-name" value={name} onChange={(e) => setName(e.target.value)} autoFocus />
            </div>
            <label className="check wide">
              <input type="checkbox" checked={shared} onChange={(e) => setShared(e.target.checked)} />
              Share it: anyone can open it or make it their home page
            </label>
          </div>
        </Dialog>
      )}
    </>
  );
}
