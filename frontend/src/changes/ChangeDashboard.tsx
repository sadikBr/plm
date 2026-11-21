import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "react-router";
import { api, unwrap, type Schemas } from "../api/client";
import { useContextsOfKind } from "../api/queries";
import { ErrorBanner, Loading, Panel, StateBadge, stateLabel, formatDate } from "../components/common";
import { BarChart } from "../reports/BarChart";
import { t, tn } from "../i18n/i18n";
import { kindLabel } from "./Changes";

type Metrics = Schemas["ChangeMetrics"];

export const useChangeMetrics = (contextId?: string) =>
  useQuery({
    queryKey: ["changeMetrics", contextId ?? ""],
    queryFn: () => unwrap(api.GET("/api/v1/change-metrics", { params: { query: contextId ? { contextId } : {} } })),
  });

const days = (n: number | null | undefined) => (n == null ? "—" : n < 10 ? n.toFixed(1) : String(Math.round(n)));

/** Change dashboards (roadmap 5.12): what's open, for how long, how fast changes close, and mine. */
export function ChangeDashboard() {
  const { data: products } = useContextsOfKind("PRODUCT");
  const [contextId, setContextId] = useState("");
  const { data, error, isLoading } = useChangeMetrics(contextId || undefined);
  if (isLoading) return <Loading />;
  if (error || !data) return <ErrorBanner error={error} />;
  const notices = data.cycleTimes.find((c) => c.kind === "CHANGE_NOTICE");
  return (
    <div className="details-stack">
      <div className="row" style={{ gap: 8 }}>
        <label htmlFor="cm-context" className="muted">
          {t("Changes in")}
        </label>
        <select id="cm-context" className="filter" value={contextId} onChange={(e) => setContextId(e.target.value)}>
          <option value="">{t("Every product I can read")}</option>
          {(products ?? []).map((c) => (
            <option key={c.id} value={c.id}>
              {c.name}
            </option>
          ))}
        </select>
      </div>
      <div className="stat-row">
        <Panel title={t("Open changes")}>
          <div className="stat-tile">
            <span className="value">{data.open}</span>
            <span className="muted small">{t("not resolved, rejected or cancelled")}</span>
          </div>
        </Panel>
        <Panel title={t("Change notices: median cycle")}>
          <div className="stat-tile">
            <span className="value">
              {days(notices?.medianDays)} <span className="unit">{t("days")}</span>
            </span>
            <span className="muted small">
              {tn(
                "from creation to resolution, {n} resolved in {d} days",
                "from creation to resolution, {n} resolved in {d} days",
                notices?.resolved ?? 0,
                {
                  d: data.periodDays,
                },
              )}
            </span>
          </div>
        </Panel>
        <Panel title={t("Mine")}>
          <div className="stat-tile">
            <span className="value">{data.mine.length}</span>
            <span className="muted small">{t("open changes I own or have a task in")}</span>
          </div>
        </Panel>
      </div>
      <div className="dashboard-grid">
        <Panel title={t("Open by state")} flush>
          <OpenByState metrics={data} />
        </Panel>
        <Panel title={t("Open for")} note={t("Days since each open change was created.")} flush>
          <AgeChart ages={data.ages} />
        </Panel>
        <Panel title={t("Opened and resolved per week")} flush>
          <FlowChart flow={data.flow} />
        </Panel>
        <Panel title={t("Cycle time")} note={t("Creation to resolution, last {d} days.", { d: data.periodDays })} flush>
          <CycleTable metrics={data} />
        </Panel>
      </div>
      <Panel title={t("My open changes")} flush>
        <MyChanges mine={data.mine} />
      </Panel>
    </div>
  );
}

export function OpenByState({ metrics }: { metrics: Metrics }) {
  return (
    <BarChart
      max={10}
      wideLabels
      bars={metrics.byState.map((s) => ({
        key: `${s.kind}/${s.state}`,
        label: `${kindLabel(s.kind)} · ${stateLabel(s.state)}`,
        count: s.count,
      }))}
    />
  );
}

const AGE_BUCKETS = (): [keyof Omit<Schemas["ChangeAges"], "kind">, string, string][] => [
  ["upTo7", t("up to a week"), "var(--seq-250)"],
  ["upTo30", t("up to a month"), "var(--seq-400)"],
  ["upTo90", t("up to 3 months"), "var(--seq-550)"],
  ["over90", t("longer"), "var(--seq-700)"],
];

/** Open changes per kind, stacked by how long they have been open: an ordinal one-hue ramp. */
function AgeChart({ ages }: { ages: Schemas["ChangeAges"][] }) {
  const [hover, setHover] = useState<string | null>(null);
  if (!ages.length) return <p className="empty">{t("Nothing open.")}</p>;
  const buckets = AGE_BUCKETS();
  const top = Math.max(1, ...ages.map((a) => a.upTo7 + a.upTo30 + a.upTo90 + a.over90));
  return (
    <div className="viz-root bar-chart">
      <div className="viz-legend" aria-hidden>
        {buckets.map(([k, label, color]) => (
          <span key={k}>
            <i style={{ background: color }} /> {label}
          </span>
        ))}
      </div>
      {ages.map((a) => {
        const total = a.upTo7 + a.upTo30 + a.upTo90 + a.over90;
        return (
          <div
            key={a.kind}
            className={`bar-row ${hover === a.kind ? "hover" : ""}`}
            onMouseEnter={() => setHover(a.kind)}
            onMouseLeave={() => setHover(null)}
            role="img"
            aria-label={`${kindLabel(a.kind)}: ${buckets.map(([k, label]) => `${a[k]} ${label}`).join(", ")}`}
          >
            <span className="bar-label">{kindLabel(a.kind)}</span>
            <span className="bar-track">
              <span className="stack" style={{ width: `${(total / top) * 100}%` }}>
                {buckets.map(([k, , color]) =>
                  a[k] ? <span key={k} className="stack-seg" style={{ flex: a[k], background: color }} /> : null,
                )}
              </span>
              <span className="bar-value">{total}</span>
              {hover === a.kind && (
                <span className="viz-tooltip" role="tooltip">
                  <strong>{kindLabel(a.kind)}</strong> {buckets.map(([k, label]) => `${label}: ${a[k]}`).join(" · ")}
                </span>
              )}
            </span>
          </div>
        );
      })}
    </div>
  );
}

/** Opened (slot 1) and resolved (slot 2) per week, as paired columns on one axis. */
function FlowChart({ flow }: { flow: Schemas["WeekFlow"][] }) {
  const [hover, setHover] = useState<string | null>(null);
  const top = Math.max(1, ...flow.flatMap((w) => [w.opened, w.resolved]));
  return (
    <div className="viz-root flow-chart">
      <div className="viz-legend">
        <span>
          <i style={{ background: "var(--series-1)" }} /> {t("Opened")}
        </span>
        <span>
          <i style={{ background: "var(--series-2)" }} /> {t("Resolved")}
        </span>
      </div>
      <div className="flow-plot" role="img" aria-label={flow.map((w) => `${w.week}: ${w.opened} / ${w.resolved}`).join(", ")}>
        <span className="flow-max">{top}</span>
        {flow.map((w, i) => (
          <div
            key={w.week}
            className={`flow-week ${hover === w.week ? "hover" : ""} ${i > flow.length / 2 ? "tip-left" : ""}`}
            onMouseEnter={() => setHover(w.week)}
            onMouseLeave={() => setHover(null)}
          >
            <div className="flow-cols">
              <span className="flow-col" style={{ height: `${(w.opened / top) * 100}%`, background: "var(--series-1)" }} />
              <span className="flow-col" style={{ height: `${(w.resolved / top) * 100}%`, background: "var(--series-2)" }} />
            </div>
            <span className="flow-label">{w.week.slice(5)}</span>
            {hover === w.week && (
              <span className="viz-tooltip" role="tooltip">
                <strong>{t("Week of {date}", { date: formatDate(w.week).split(",")[0] ?? w.week })}</strong> ·{" "}
                {t("{opened} opened, {resolved} resolved", { opened: w.opened, resolved: w.resolved })}
              </span>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}

function CycleTable({ metrics }: { metrics: Metrics }) {
  if (!metrics.cycleTimes.length) return <p className="empty">{t("Nothing resolved in the period.")}</p>;
  return (
    <table className="grid">
      <thead>
        <tr>
          <th>{t("Kind")}</th>
          <th className="num">{t("Resolved")}</th>
          <th className="num">{t("Median (days)")}</th>
          <th className="num">{t("Average (days)")}</th>
        </tr>
      </thead>
      <tbody>
        {metrics.cycleTimes.map((c) => (
          <tr key={c.kind}>
            <td>{kindLabel(c.kind)}</td>
            <td className="num">{c.resolved}</td>
            <td className="num">{days(c.medianDays)}</td>
            <td className="num">{days(c.averageDays)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

export function MyChanges({ mine, compact }: { mine: Schemas["MyChange"][]; compact?: boolean }) {
  if (!mine.length) return <p className="empty">{t("No open change is yours.")}</p>;
  return (
    <table className="grid">
      <thead>
        <tr>
          <th>{t("Change")}</th>
          {!compact && <th>{t("Kind")}</th>}
          <th>{t("State")}</th>
          <th>{t("Why")}</th>
          <th className="num">{t("Open for")}</th>
          <th>{t("Due")}</th>
        </tr>
      </thead>
      <tbody>
        {mine.map((c) => (
          <tr key={c.id}>
            <td>
              <Link to={`/objects/${c.id}?tab=change`}>{c.number}</Link> {c.name}
            </td>
            {!compact && <td>{kindLabel(c.kind)}</td>}
            <td>
              <StateBadge state={c.state} />
            </td>
            <td>{c.why === "ASSIGNEE" ? t("Assigned to me") : t("I own it")}</td>
            <td className="num">{tn("{n} day", "{n} days", c.ageDays)}</td>
            <td className="nowrap">{c.due ?? ""}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
