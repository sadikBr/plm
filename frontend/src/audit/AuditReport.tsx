import { useQueries, useQuery } from "@tanstack/react-query";
import { FileSpreadsheet, ScrollText, X } from "lucide-react";
import { useState } from "react";
import { Link, useSearchParams } from "react-router";
import { api, unwrap } from "../api/client";
import { findPrincipalByUsername, useContextById, useMe } from "../api/queries";
import { ErrorBanner, Loading, Panel, PrincipalName, formatDate } from "../components/common";
import { PageHeader } from "../components/Layout";
import { BarChart } from "../reports/BarChart";
import { t } from "../i18n/i18n";

/** `versioning.ObjectCheckedIn.v1` → "Checked in". */
export function auditAction(action: string): string {
  const name = action
    .replace(/^\w+\./, "")
    .replace(/\.v\d+$/, "")
    .replace(/^Object/, "");
  const words = name.replace(/([a-z])([A-Z])/g, "$1 $2").toLowerCase();
  const english = words.charAt(0).toUpperCase() + words.slice(1);
  switch (english) {
    case "Created":
      return t("Created");
    case "Checked out":
      return t("Checked out");
    case "Checked in":
      return t("Checked in");
    case "Check out undone":
      return t("Check out undone");
    case "Working copy updated":
      return t("Working copy updated");
    case "Revised":
      return t("Revised");
    case "State changed":
      return t("State changed");
    case "Renamed":
      return t("Renamed");
    case "Lifecycle changed":
      return t("Lifecycle changed");
    case "Moved":
      return t("Moved");
    case "Copied":
      return t("Copied");
    case "Deleted":
      return t("Deleted");
    case "Shared":
      return t("Shared");
    case "Unshared":
      return t("Unshared");
    case "Ownership changed":
      return t("Ownership changed");
    case "Folder changed":
      return t("Folder changed");
    case "Content changed":
      return t("Content changed");
    case "Comment posted":
      return t("Comment posted");
    case "Context created":
      return t("Context created");
    case "Context template changed":
      return t("Context template changed");
    case "Policy changed":
      return t("Policy changed");
    case "Shared team changed":
      return t("Shared team changed");
    case "Team member put":
      return t("Team member put");
    case "Team member removed":
      return t("Team member removed");
    case "Team role changed":
      return t("Team role changed");
    case "Lifecycle assigned":
      return t("Lifecycle assigned");
    case "Lifecycle versioned":
      return t("Lifecycle versioned");
    case "Extension action invoked":
      return t("Extension action invoked");
    case "Job finished":
      return t("Job finished");
    case "Job schedule changed":
      return t("Job schedule changed");
    case "Preference changed":
      return t("Preference changed");
    case "Configuration imported":
      return t("Configuration imported");
  }
  return english;
}

const AREAS = (): [string, string][] => [
  ["", t("Everything")],
  ["versioning.", t("Objects")],
  ["collaboration.", t("Discussions")],
  ["versioning.ObjectShared", t("Sharing")],
  ["access.", t("Teams and access")],
  ["jobs.", t("Background jobs")],
];

/** Day boundaries in the browser's time zone, as instants. */
const startOf = (day: string) => (day ? new Date(`${day}T00:00:00`).toISOString() : undefined);
const endOf = (day: string) => (day ? new Date(new Date(`${day}T00:00:00`).getTime() + 86_400_000).toISOString() : undefined);

function usePrincipalNames(ids: string[]): Map<string, string> {
  return useQueries({
    queries: ids.map((id) => ({
      queryKey: ["principal", id],
      queryFn: () => unwrap(api.GET("/api/v1/principals/{id}", { params: { path: { id } } })),
      staleTime: 5 * 60_000,
      retry: false,
    })),
    combine: (results) => new Map(results.flatMap((r, i) => (r.data ? [[ids[i]!, r.data.displayName] as [string, string]] : []))),
  });
}

/**
 * Audit report (roadmap 3.10): who did what, when, filtered by period, person, kind of change and
 * context; counts by kind and by person; exported to Excel. Site administrators see everything,
 * a context's managers that context.
 */
export function AuditReportPage() {
  const { data: me } = useMe();
  const [params, setParams] = useSearchParams();
  const contextId = params.get("contextId") ?? undefined;
  const { data: context } = useContextById(contextId);
  const [from, setFrom] = useState(() => new Date(Date.now() - 30 * 86_400_000).toISOString().slice(0, 10));
  const [to, setTo] = useState("");
  const [who, setWho] = useState("");
  const [whoId, setWhoId] = useState<string | undefined>();
  const [whoError, setWhoError] = useState<unknown>();
  const [action, setAction] = useState(params.get("action") ?? "");

  const query = { from: startOf(from), to: endOf(to), actor: whoId, action: action || undefined, contextId, limit: 300 };
  const { data, error, isFetching } = useQuery({
    queryKey: ["auditReport", query],
    queryFn: () => unwrap(api.GET("/api/v1/audit/report", { params: { query } })),
    enabled: !!me && (me.siteAdmin || !!contextId),
  });
  const names = usePrincipalNames(data?.byActor.map((a) => a.key) ?? []);

  const applyWho = async () => {
    setWhoError(undefined);
    if (!who.trim()) return setWhoId(undefined);
    try {
      setWhoId((await findPrincipalByUsername(who.trim())).id);
    } catch (e) {
      setWhoError(e);
    }
  };

  const exportRows = async () => {
    if (!data) return;
    const rows = data.entries.map((e) => [
      new Date(e.occurredAt).toLocaleString(),
      auditAction(e.action),
      names.get(e.actor) ?? e.actor,
      String(e.details.number ?? ""),
      String(e.details.version ?? ""),
      describe(e.details),
    ]);
    const res = await api.POST("/api/v1/export/xlsx", {
      body: { sheetName: t("Audit"), columns: [t("When"), t("What"), t("Who"), t("Number"), t("Version"), t("Details")], rows },
      parseAs: "blob",
      headers: { Accept: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" },
    });
    if (!res.data) return;
    const url = URL.createObjectURL(res.data as Blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `${t("Audit {from} to {to}", { from: from || t("start"), to: to || t("today") })}.xlsx`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 10_000);
  };

  return (
    <>
      <PageHeader
        icon={<ScrollText size={18} className="icon-muted" aria-hidden />}
        title={<span className="nm">{t("Audit report")}</span>}
        meta={
          <span className="sub">
            {contextId ? (
              <>
                {t("Changes in")} <Link to={`/contexts/${contextId}`}>{context?.name ?? "…"}</Link> {t("and below")}
                {me?.siteAdmin && (
                  <button className="icon-btn" aria-label={t("All contexts")} title={t("All contexts")} onClick={() => setParams({})}>
                    <X size={12} />
                  </button>
                )}
              </>
            ) : (
              t("Every recorded change, by anyone")
            )}
          </span>
        }
        toolbar={
          <button className="btn ghost" disabled={!data?.entries.length} onClick={() => void exportRows()}>
            <FileSpreadsheet size={15} aria-hidden /> {t("Export to Excel")}
          </button>
        }
      />
      <div className="content audit-report">
        <form
          className="audit-filters"
          onSubmit={(e) => {
            e.preventDefault();
            void applyWho();
          }}
        >
          <div className="field">
            <label htmlFor="audit-from">{t("From")}</label>
            <input id="audit-from" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="audit-to">{t("To")}</label>
            <input id="audit-to" type="date" value={to} onChange={(e) => setTo(e.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="audit-what">{t("What")}</label>
            <select id="audit-what" value={action} onChange={(e) => setAction(e.target.value)}>
              {AREAS().map(([k, l]) => (
                <option key={k} value={k}>
                  {l}
                </option>
              ))}
              {action && !AREAS().some(([k]) => k === action) && <option value={action}>{auditAction(action)}</option>}
            </select>
          </div>
          <div className="field">
            <label htmlFor="audit-who">{t("Who (username)")}</label>
            <input
              id="audit-who"
              value={who}
              onChange={(e) => setWho(e.target.value)}
              onBlur={() => void applyWho()}
              placeholder={t("Anyone")}
            />
          </div>
        </form>
        <ErrorBanner error={whoError ?? error} />
        {!me?.siteAdmin && !contextId && <p className="empty">{t("Open the audit report from a context you manage.")}</p>}
        {data && (
          <>
            <div className="audit-charts">
              <Panel title={t("Changes")} note={isFetching ? t("updating…") : undefined}>
                <div className="stat-tile">
                  <span className="value">{data.total}</span>
                  <span className="muted small">{t("recorded in the period")}</span>
                </div>
              </Panel>
              <Panel title={t("By kind of change")}>
                <BarChart
                  bars={data.byAction.map((a) => ({ key: a.key, label: auditAction(a.key), count: a.count }))}
                  onSelect={(k) => setAction(k.replace(/\.v\d+$/, ""))}
                />
              </Panel>
              <Panel title={t("By person")}>
                <BarChart bars={data.byActor.map((a) => ({ key: a.key, label: names.get(a.key) ?? a.key, count: a.count }))} />
              </Panel>
            </div>
            <Panel
              title={t("Changes")}
              note={
                data.entries.length < data.total
                  ? t("latest {count} of {total}", { count: data.entries.length, total: data.total })
                  : undefined
              }
              flush
            >
              {data.entries.length === 0 ? (
                <p className="empty">{t("Nothing recorded for these filters.")}</p>
              ) : (
                <div className="grid-wrap">
                  <table className="grid" aria-label={t("Audit entries")}>
                    <thead>
                      <tr>
                        <th>{t("When")}</th>
                        <th>{t("What")}</th>
                        <th>{t("Who")}</th>
                        <th>{t("Object")}</th>
                        <th>{t("Details")}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.entries.map((e) => (
                        <tr key={e.seq}>
                          <td className="nowrap muted date">{formatDate(e.occurredAt)}</td>
                          <td className="nowrap">{auditAction(e.action)}</td>
                          <td className="nowrap">
                            <PrincipalName id={e.actor} />
                          </td>
                          <td className="nowrap">
                            {e.aggregateType === "versioned-object" && e.action !== "versioning.ObjectDeleted.v1" ? (
                              <Link className="num-link" to={`/objects/${e.aggregateId}`}>
                                {String(e.details.number ?? t("Object"))}
                              </Link>
                            ) : (
                              <span>{String(e.details.number ?? e.details.name ?? "")}</span>
                            )}
                            {e.details.version != null && <span className="version"> {String(e.details.version)}</span>}
                          </td>
                          <td className="muted">
                            {describe(e.details)}
                            {typeof e.details.principalId === "string" && (
                              <>
                                {" "}
                                <PrincipalName id={e.details.principalId} />
                              </>
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </Panel>
          </>
        )}
        {!data && (me?.siteAdmin || contextId) && !error && <Loading />}
      </div>
    </>
  );
}

function describe(d: Record<string, unknown>): string {
  const parts: string[] = [];
  if (d.from != null && d.to != null) parts.push(`${String(d.from)} → ${String(d.to)}`);
  if (d.previousNumber != null) parts.push(t("was {number} “{name}”", { number: String(d.previousNumber), name: String(d.previousName) }));
  if (d.principalId != null && Array.isArray(d.permissions))
    parts.push(
      t("shared with {principal} ({permissions})", {
        principal: String(d.principalId),
        permissions: (d.permissions as string[]).join(", ").toLowerCase(),
      }),
    );
  if (d.comment) parts.push(`“${String(d.comment)}”`);
  if (d.name != null && d.previousNumber == null && d.number != null) parts.push(String(d.name));
  return parts.join(" · ");
}
