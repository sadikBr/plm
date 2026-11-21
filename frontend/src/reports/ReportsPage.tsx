import { FavoriteButton } from "../personal/Personal";
import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { BarChart3, CalendarClock, Mail, Pencil, Play, Plus, Share2, Trash2 } from "lucide-react";
import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { api, unwrap, type Schemas } from "../api/client";
import { findPrincipalByUsername, useLifecycles, useMe, useTypes, type VersionedObject } from "../api/queries";
import { pickLabel } from "../components/AttributeForm";
import { Dialog, ErrorBanner, Loading, Panel, PrincipalName, formatDate, stateLabel, typeLabel } from "../components/common";
import { PageHeader } from "../components/Layout";
import {
  COLUMN_LABELS,
  ObjectTable,
  columnLabel,
  useAttributeDefinitions,
  useCellText,
  type BuiltinColumn,
  type ColumnKey,
} from "../components/ObjectTable";
import { exportXlsx } from "../components/TableViews";
import { describeCron } from "../admin/JobsAdmin";
import { BarChart, type Bar } from "./BarChart";
import { language, t, tn } from "../i18n/i18n";

type Report = Schemas["Report"];
type Settings = Schemas["ReportSettings"];
type Row = Schemas["ReportRow"];
export type ReportResult = Schemas["ReportResult"];

const BLANK: Settings = {
  name: "",
  description: "",
  query: { text: "", typeIds: [], states: [], contextIds: [], attributes: {}, offset: 0, limit: 25 },
  columns: ["number", "name", "type", "version", "state", "modified"],
  groupBy: "state",
  shared: false,
  cron: null,
  timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone ?? "UTC",
  recipients: [],
  alert: "ALWAYS",
  threshold: null,
};

/** A report row in the object table's shape, so the same columns, sorting and export apply. */
export function asObject(r: Row): VersionedObject {
  const [revision, iteration] = r.version.split(".");
  return {
    id: r.id,
    number: r.number,
    name: r.name,
    typeId: r.typeId,
    contextId: r.contextId,
    version: r.version,
    checkedOutBy: r.checkedOutBy ?? null,
    owner: r.owner,
    reservedBy: null,
    folderId: null,
    createdAt: r.createdAt,
    createdBy: r.owner,
    latest: {
      id: r.id,
      revision: revision ?? "",
      iteration: Number(iteration ?? 0),
      version: r.version,
      status: "CHECKED_IN",
      state: r.state,
      attributes: r.attributes,
      basedOnId: null,
      comment: null,
      createdAt: r.modifiedAt,
      createdBy: r.owner,
      checkedInAt: r.modifiedAt,
      checkedOutBy: null,
    },
  } as VersionedObject;
}

export const useReportRun = (id?: string) =>
  useQuery({
    queryKey: ["reportRun", id],
    queryFn: () => unwrap(api.POST("/api/v1/reports/{id}/run", { params: { path: { id: id! } } })),
    enabled: !!id,
    staleTime: 30_000,
  });

/** Bars of a report's grouping, with readable labels. */
export function useBars(result?: ReportResult): Bar[] {
  const { data: types } = useTypes();
  const { data: lifecycles } = useLifecycles();
  const groupBy = result?.report.settings.groupBy ?? "";
  const contexts = useQueries({
    queries: (groupBy === "context" ? (result?.groups ?? []) : []).map((g) => ({
      queryKey: ["contextById", g.key],
      queryFn: () => unwrap(api.GET("/api/v1/contexts/{id}", { params: { path: { id: g.key } } })),
    })),
  });
  const people = useQueries({
    queries: (groupBy === "owner" ? (result?.groups ?? []) : []).map((g) => ({
      queryKey: ["principal", g.key],
      queryFn: () => unwrap(api.GET("/api/v1/principals/{id}", { params: { path: { id: g.key } } })),
    })),
  });
  const stateName = (k: string) => {
    for (const lc of lifecycles ?? []) {
      const s = lc.states.find((x) => x.key === k);
      if (s) return pickLabel(s.labels, k);
    }
    return stateLabel(k);
  };
  return (result?.groups ?? []).map((g, i) => ({
    key: g.key,
    count: g.count,
    label:
      groupBy === "state"
        ? stateName(g.key)
        : groupBy === "type"
          ? typeLabel(types?.find((t) => t.id === g.key))
          : groupBy === "context"
            ? (contexts[i]?.data?.name ?? "…")
            : groupBy === "owner"
              ? (people[i]?.data?.displayName ?? "…")
              : g.key || t("(empty)"),
  }));
}

/** Reports (roadmap 3.8): saved searches with columns, counts and charts; scheduled by mail. */
export function ReportsPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const { data: me } = useMe();
  const { data: reports, isLoading } = useQuery({ queryKey: ["reports"], queryFn: () => unwrap(api.GET("/api/v1/reports")) });
  const [editing, setEditing] = useState<Report | "new" | null>(null);
  const current = reports?.find((r) => r.id === id) ?? (id ? undefined : reports?.[0]);

  return (
    <>
      <PageHeader
        icon={<BarChart3 size={18} className="icon-muted" aria-hidden />}
        title={<span className="nm">{t("Reports")}</span>}
        meta={<span className="sub">{t("Searches with columns and counts; on demand, or mailed on a schedule")}</span>}
        toolbar={
          <button className="btn ghost" onClick={() => setEditing("new")}>
            <Plus size={15} aria-hidden /> {t("New report")}
          </button>
        }
      />
      <div className="content reports-layout">
        <Panel title={t("Reports")} flush>
          {isLoading ? (
            <Loading />
          ) : !reports?.length ? (
            <p className="empty">{t("No reports yet.")}</p>
          ) : (
            <ul className="report-list" aria-label={t("Reports")}>
              {reports.map((r) => (
                <li key={r.id} className={current?.id === r.id ? "selected" : undefined}>
                  <Link to={`/reports/${r.id}`}>{r.settings.name}</Link>
                  <span className="row" style={{ gap: 4 }}>
                    {r.settings.cron && <CalendarClock size={12} className="icon-muted" aria-label={t("Scheduled")} />}
                    {r.settings.shared && <Share2 size={12} className="icon-muted" aria-label={t("Shared")} />}
                    {r.owner !== me?.user.id && (
                      <span className="muted small">
                        <PrincipalName id={r.owner} />
                      </span>
                    )}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </Panel>
        {current ? (
          <ReportView key={current.id} report={current} mine={current.owner === me?.user.id} onEdit={() => setEditing(current)} />
        ) : (
          !isLoading && <p className="empty">{t("Choose a report, or make one.")}</p>
        )}
      </div>
      {editing && (
        <ReportEditor
          report={editing === "new" ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={(r) => {
            setEditing(null);
            navigate(`/reports/${r.id}`);
          }}
        />
      )}
    </>
  );
}

function ReportView({ report, mine, onEdit }: { report: Report; mine: boolean; onEdit: () => void }) {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const { data: result, error, isFetching, refetch } = useReportRun(report.id);
  const { data: runs } = useQuery({
    queryKey: ["reportRuns", report.id],
    queryFn: () => unwrap(api.GET("/api/v1/reports/{id}/runs", { params: { path: { id: report.id } } })),
    enabled: !!report.settings.cron,
  });
  const remove = useMutation({
    mutationFn: () => unwrap(api.DELETE("/api/v1/reports/{id}", { params: { path: { id: report.id } } })),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["reports"] });
      navigate("/reports");
    },
  });
  const rows = (result?.rows ?? []).map(asObject);
  const definitions = useAttributeDefinitions(rows.map((r) => r.typeId));
  const text = useCellText(definitions);
  const bars = useBars(result);
  const [sort, setSort] = useState<{ column: ColumnKey; desc: boolean }>({ column: "number", desc: false });
  const s = report.settings;

  return (
    <div style={{ display: "grid", gap: 12, alignContent: "start", minWidth: 0 }}>
      <Panel
        title={s.name}
        note={s.description ?? undefined}
        actions={
          <>
            <FavoriteButton kind="REPORT" targetId={report.id} label={s.name} />
            <button className="btn small" onClick={() => void refetch()} disabled={isFetching}>
              <Play size={12} aria-hidden /> {t("Run now")}
            </button>
            <button
              className="btn small"
              disabled={!rows.length}
              onClick={() => void exportXlsx(s.name, s.columns as ColumnKey[], rows, definitions, text)}
            >
              {t("Export to Excel")}
            </button>
            {mine && (
              <>
                <button className="btn small" onClick={onEdit}>
                  <Pencil size={12} aria-hidden /> {t("Edit")}
                </button>
                <button
                  className="icon-btn"
                  aria-label={t("Delete the report")}
                  onClick={() => window.confirm(t("Delete the report “{name}”?", { name: s.name })) && remove.mutate()}
                >
                  <Trash2 size={13} />
                </button>
              </>
            )}
          </>
        }
      >
        <div className="report-facts">
          <div className="stat-tile">
            <span className="muted small">{t("Found")}</span>
            <span className="value">
              {result ? result.total : "…"}
              {result?.capped ? "+" : ""}
            </span>
            <span className="muted small">{result ? t("run {date}", { date: formatDate(result.ranAt) }) : ""}</span>
          </div>
          <div style={{ display: "grid", gap: 4, alignContent: "start" }}>
            <span className="muted small">{t("Finds")}</span>
            <span>{describeQuery(s.query)}</span>
            {s.cron ? (
              <span className="row" style={{ gap: 6 }}>
                <Mail size={13} className="icon-muted" aria-hidden />
                {t("{schedule} ({zone}) to {recipients}, {alert}", {
                  schedule: describeCron(s.cron) || s.cron,
                  zone: s.timeZone,
                  recipients: tn("{n} recipient", "{n} recipients", s.recipients.length),
                  alert: `${inSentence(ALERTS()[s.alert] ?? "")}${s.alert === "MORE_THAN" ? ` ${s.threshold}` : ""}`,
                })}
                {report.nextRunAt && <span className="muted small">{t("· next {date}", { date: formatDate(report.nextRunAt) })}</span>}
              </span>
            ) : (
              <span className="muted small">{t("Run on demand")}</span>
            )}
          </div>
        </div>
        <ErrorBanner error={error} />
      </Panel>
      {s.groupBy && (
        <Panel title={t("By {group}", { group: groupLabel(s.groupBy, definitions) })} flush>
          {result ? <BarChart bars={bars} /> : <Loading />}
        </Panel>
      )}
      <Panel title={t("Objects")} note={result ? `${rows.length}` : undefined} flush>
        {!result ? (
          <Loading />
        ) : (
          <ObjectTable label={t("Report results")} objects={rows} columns={s.columns as ColumnKey[]} sort={sort} onSort={setSort} />
        )}
      </Panel>
      {!!runs?.length && (
        <Panel title={t("Scheduled runs")} flush>
          <table className="grid" aria-label={t("Scheduled runs")}>
            <thead>
              <tr>
                <th>{t("Ran")}</th>
                <th>{t("Found")}</th>
                <th>{t("New")}</th>
                <th>{t("Mailed to")}</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {runs.map((r) => (
                <tr key={r.id}>
                  <td className="nowrap">{formatDate(r.ranAt)}</td>
                  <td>{r.total}</td>
                  <td>{r.newCount}</td>
                  <td>{r.sentTo || "—"}</td>
                  <td className="muted small">{r.note}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Panel>
      )}
    </div>
  );
}

const ALERTS = (): Record<string, string> => ({
  ALWAYS: t("Every time"),
  ANY: t("Only when something is found"),
  MORE_THAN: t("Only when more than"),
  NEW: t("Only when new objects match"),
});

/** Lower case for use inside a sentence; German nouns keep their capital. */
const inSentence = (s: string) => (language() === "de" ? s : s.toLowerCase());

export function groupLabel(groupBy: string, definitions: Map<string, Schemas["AttributeResponse"]>) {
  if (groupBy.startsWith("attr:")) return inSentence(columnLabel(groupBy as ColumnKey, definitions));
  switch (groupBy) {
    case "state":
      return t("state");
    case "type":
      return t("type");
    case "context":
      return t("context");
    case "owner":
      return t("owner");
  }
  return groupBy;
}

function describeQuery(q: Settings["query"]): string {
  const parts: string[] = [];
  if (q.text) parts.push(`“${q.text}”`);
  if (q.states.length) parts.push(t("in {states}", { states: q.states.map(stateLabel).join(` ${t("or")} `) }));
  if (q.typeIds.length) parts.push(tn("{n} type", "{n} types", q.typeIds.length));
  for (const [k, v] of Object.entries(q.attributes)) parts.push(t("{name} contains “{value}”", { name: k, value: v }));
  return parts.length ? parts.join(", ") : t("Everything you can read");
}

const SCHEDULES = (): [string, string][] => [
  ["", t("Not scheduled")],
  ["0 7 * * *", t("Every day at 07:00")],
  ["0 7 * * 1-5", t("Weekdays at 07:00")],
  ["0 7 * * 1", t("Mondays at 07:00")],
  ["0 * * * *", t("Every hour")],
];

function ReportEditor({ report, onClose, onSaved }: { report: Report | null; onClose: () => void; onSaved: (r: Report) => void }) {
  const qc = useQueryClient();
  const { data: types } = useTypes();
  const { data: lifecycles } = useLifecycles();
  const [s, setS] = useState<Settings>(report?.settings ?? BLANK);
  const [recipients, setRecipients] = useState((report?.settings.recipients ?? []).join(", "));
  const definitions = useAttributeDefinitions(types?.filter((x) => x.instantiable).map((x) => x.id) ?? []);
  const [attr, setAttr] = useState<[string, string]>(Object.entries(s.query.attributes)[0] ?? ["", ""]);
  const states = new Map<string, string>();
  for (const lc of lifecycles ?? []) for (const st of lc.states) if (!states.has(st.key)) states.set(st.key, pickLabel(st.labels, st.key));
  const settings = (): Settings => ({ ...s, query: { ...s.query, attributes: attr[0] && attr[1] ? { [attr[0]]: attr[1] } : {} } });
  const preview = useMutation({ mutationFn: () => unwrap(api.POST("/api/v1/reports/preview", { body: settings() })) });
  const save = useMutation({
    mutationFn: async () => {
      // People by username, others by address.
      const list = await Promise.all(
        recipients
          .split(/[,\s]+/)
          .map((x) => x.trim())
          .filter(Boolean)
          .map(async (x) => (x.includes("@") ? x : (await findPrincipalByUsername(x)).id)),
      );
      const body = { ...settings(), recipients: list };
      return report
        ? unwrap(api.PUT("/api/v1/reports/{id}", { params: { path: { id: report.id } }, body }))
        : unwrap(api.POST("/api/v1/reports", { body }));
    },
    onSuccess: (r) => {
      void qc.invalidateQueries({ queryKey: ["reports"] });
      void qc.invalidateQueries({ queryKey: ["reportRun", r.id] });
      onSaved(r);
    },
  });
  const toggle = (key: "states" | "typeIds", v: string) =>
    setS({ ...s, query: { ...s.query, [key]: s.query[key].includes(v) ? s.query[key].filter((x) => x !== v) : [...s.query[key], v] } });
  const allColumns: ColumnKey[] = [
    ...(Object.keys(COLUMN_LABELS) as BuiltinColumn[]),
    ...[...definitions.keys()].sort().map((n) => `attr:${n}` as ColumnKey),
  ];

  return (
    <Dialog
      open
      wide
      title={report ? t("Edit {name}", { name: report.settings.name }) : t("New report")}
      onClose={onClose}
      footer={
        <>
          <span className="muted small grow">
            {preview.data ? t("Finds {count} now", { count: `${preview.data.total}${preview.data.capped ? "+" : ""}` }) : ""}
          </span>
          <button className="btn" onClick={() => preview.mutate()} disabled={preview.isPending}>
            {t("Try it")}
          </button>
          <button className="btn" onClick={onClose}>
            {t("Cancel")}
          </button>
          <button className="btn primary" disabled={save.isPending || !s.name.trim()} onClick={() => save.mutate()}>
            {t("Save")}
          </button>
        </>
      }
    >
      <div className="attr-form">
        <ErrorBanner error={save.error ?? preview.error} />
        <div className="field">
          <label htmlFor="rep-name">{t("Name")}</label>
          <input id="rep-name" value={s.name} onChange={(e) => setS({ ...s, name: e.target.value })} autoFocus />
        </div>
        <div className="field">
          <label htmlFor="rep-desc">{t("Description")}</label>
          <input id="rep-desc" value={s.description ?? ""} onChange={(e) => setS({ ...s, description: e.target.value })} />
        </div>
        <div className="section-label wide">{t("What it finds")}</div>
        <div className="field wide">
          <label htmlFor="rep-text">{t("Words")}</label>
          <input
            id="rep-text"
            value={s.query.text ?? ""}
            onChange={(e) => setS({ ...s, query: { ...s.query, text: e.target.value } })}
            placeholder={t("Optional: words in numbers, names, values, files")}
          />
        </div>
        <fieldset className="check-list wide">
          <legend>{t("States (none: any)")}</legend>
          {[...states.entries()].map(([k, l]) => (
            <label key={k} className="check">
              <input type="checkbox" checked={s.query.states.includes(k)} onChange={() => toggle("states", k)} />
              {l}
            </label>
          ))}
        </fieldset>
        <fieldset className="check-list wide">
          <legend>{t("Types (none: any)")}</legend>
          {types
            ?.filter((x) => x.instantiable)
            .map((x) => (
              <label key={x.id} className="check">
                <input type="checkbox" checked={s.query.typeIds.includes(x.id)} onChange={() => toggle("typeIds", x.id)} />
                {typeLabel(x)}
              </label>
            ))}
        </fieldset>
        <div className="field">
          <label htmlFor="rep-attr">{t("Attribute")}</label>
          <select id="rep-attr" value={attr[0]} onChange={(e) => setAttr([e.target.value, attr[1]])}>
            <option value="">{t("(none)")}</option>
            {[...definitions.values()].map((d) => (
              <option key={d.name} value={d.name}>
                {pickLabel(d.labels, d.name)}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="rep-attr-value">{t("Contains")}</label>
          <input id="rep-attr-value" value={attr[1]} onChange={(e) => setAttr([attr[0], e.target.value])} />
        </div>
        <div className="section-label wide">{t("What it shows")}</div>
        <fieldset className="check-list wide tall">
          <legend>{t("Columns")}</legend>
          {allColumns.map((c) => (
            <label key={c} className="check">
              <input
                type="checkbox"
                checked={s.columns.includes(c)}
                onChange={() => setS({ ...s, columns: s.columns.includes(c) ? s.columns.filter((x) => x !== c) : [...s.columns, c] })}
              />
              {columnLabel(c, definitions)}
            </label>
          ))}
        </fieldset>
        <div className="field">
          <label htmlFor="rep-group">{t("Chart: count by")}</label>
          <select id="rep-group" value={s.groupBy ?? ""} onChange={(e) => setS({ ...s, groupBy: e.target.value || null })}>
            <option value="">{t("No chart")}</option>
            <option value="state">{t("State")}</option>
            <option value="type">{t("Type")}</option>
            <option value="context">{t("Context")}</option>
            <option value="owner">{t("Owner")}</option>
            {[...definitions.values()].map((d) => (
              <option key={d.name} value={`attr:${d.name}`}>
                {pickLabel(d.labels, d.name)}
              </option>
            ))}
          </select>
        </div>
        <label className="check" style={{ alignSelf: "end" }}>
          <input type="checkbox" checked={s.shared} onChange={(e) => setS({ ...s, shared: e.target.checked })} />
          {t("Share it (others run it with their own access)")}
        </label>
        <div className="section-label wide">{t("Schedule and alerts")}</div>
        <div className="field">
          <label htmlFor="rep-cron">{t("Mail it")}</label>
          <select
            id="rep-cron"
            value={SCHEDULES().some(([c]) => c === (s.cron ?? "")) ? (s.cron ?? "") : "custom"}
            onChange={(e) => setS({ ...s, cron: e.target.value === "custom" ? (s.cron ?? "0 7 * * *") : e.target.value || null })}
          >
            {SCHEDULES().map(([c, l]) => (
              <option key={c} value={c}>
                {l}
              </option>
            ))}
            <option value="custom">{t("Custom (cron)…")}</option>
          </select>
          {s.cron && !SCHEDULES().some(([c]) => c === s.cron) && (
            <input aria-label={t("Cron")} className="code" value={s.cron} onChange={(e) => setS({ ...s, cron: e.target.value })} />
          )}
        </div>
        <div className="field">
          <label htmlFor="rep-alert">{t("Send")}</label>
          <select
            id="rep-alert"
            value={s.alert}
            disabled={!s.cron}
            onChange={(e) => setS({ ...s, alert: e.target.value as Settings["alert"] })}
          >
            {Object.entries(ALERTS()).map(([k, l]) => (
              <option key={k} value={k}>
                {l}
              </option>
            ))}
          </select>
          {s.alert === "MORE_THAN" && (
            <input
              aria-label={t("Threshold")}
              type="number"
              min={0}
              value={s.threshold ?? 0}
              onChange={(e) => setS({ ...s, threshold: Number(e.target.value) })}
            />
          )}
        </div>
        <div className="field wide">
          <label htmlFor="rep-to">{t("To")}</label>
          <input
            id="rep-to"
            value={recipients}
            disabled={!s.cron}
            onChange={(e) => setRecipients(e.target.value)}
            placeholder={t("Usernames or e-mail addresses, comma-separated")}
          />
          <span className="hint">
            {t("Runs with your access, at {zone}.", { zone: s.timeZone })} {s.cron ? describeCron(s.cron) : ""}
          </span>
        </div>
      </div>
    </Dialog>
  );
}
