import { useQueries, useQuery } from "@tanstack/react-query";
import { FileSpreadsheet, ScrollText, X } from "lucide-react";
import { useState } from "react";
import { Link, useSearchParams } from "react-router";
import { api, unwrap } from "../api/client";
import { findPrincipalByUsername, useContextById, useMe } from "../api/queries";
import { ErrorBanner, Loading, Panel, PrincipalName, formatDate } from "../components/common";
import { PageHeader } from "../components/Layout";
import { BarChart } from "../reports/BarChart";

/** `versioning.ObjectCheckedIn.v1` → "Checked in". */
export function auditAction(action: string): string {
  const name = action
    .replace(/^\w+\./, "")
    .replace(/\.v\d+$/, "")
    .replace(/^Object/, "");
  const words = name.replace(/([a-z])([A-Z])/g, "$1 $2").toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

const AREAS: [string, string][] = [
  ["", "Everything"],
  ["versioning.", "Objects"],
  ["collaboration.", "Discussions"],
  ["versioning.ObjectShared", "Sharing"],
  ["access.", "Teams and access"],
  ["jobs.", "Background jobs"],
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
      body: { sheetName: "Audit", columns: ["When", "What", "Who", "Number", "Version", "Details"], rows },
      parseAs: "blob",
      headers: { Accept: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" },
    });
    if (!res.data) return;
    const url = URL.createObjectURL(res.data as Blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `Audit ${from || "start"} to ${to || "today"}.xlsx`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 10_000);
  };

  return (
    <>
      <PageHeader
        icon={<ScrollText size={18} className="icon-muted" aria-hidden />}
        title={<span className="nm">Audit report</span>}
        meta={
          <span className="sub">
            {contextId ? (
              <>
                Changes in <Link to={`/contexts/${contextId}`}>{context?.name ?? "…"}</Link> and below
                {me?.siteAdmin && (
                  <button className="icon-btn" aria-label="All contexts" title="All contexts" onClick={() => setParams({})}>
                    <X size={12} />
                  </button>
                )}
              </>
            ) : (
              "Every recorded change, by anyone"
            )}
          </span>
        }
        toolbar={
          <button className="btn ghost" disabled={!data?.entries.length} onClick={() => void exportRows()}>
            <FileSpreadsheet size={15} aria-hidden /> Export to Excel
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
            <label htmlFor="audit-from">From</label>
            <input id="audit-from" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="audit-to">To</label>
            <input id="audit-to" type="date" value={to} onChange={(e) => setTo(e.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="audit-what">What</label>
            <select id="audit-what" value={action} onChange={(e) => setAction(e.target.value)}>
              {AREAS.map(([k, l]) => (
                <option key={k} value={k}>
                  {l}
                </option>
              ))}
              {action && !AREAS.some(([k]) => k === action) && <option value={action}>{auditAction(action)}</option>}
            </select>
          </div>
          <div className="field">
            <label htmlFor="audit-who">Who (username)</label>
            <input
              id="audit-who"
              value={who}
              onChange={(e) => setWho(e.target.value)}
              onBlur={() => void applyWho()}
              placeholder="Anyone"
            />
          </div>
        </form>
        <ErrorBanner error={whoError ?? error} />
        {!me?.siteAdmin && !contextId && <p className="empty">Open the audit report from a context you manage.</p>}
        {data && (
          <>
            <div className="audit-charts">
              <Panel title="Changes" note={isFetching ? "updating…" : undefined}>
                <div className="stat-tile">
                  <span className="value">{data.total}</span>
                  <span className="muted small">recorded in the period</span>
                </div>
              </Panel>
              <Panel title="By kind of change">
                <BarChart
                  bars={data.byAction.map((a) => ({ key: a.key, label: auditAction(a.key), count: a.count }))}
                  onSelect={(k) => setAction(k.replace(/\.v\d+$/, ""))}
                />
              </Panel>
              <Panel title="By person">
                <BarChart bars={data.byActor.map((a) => ({ key: a.key, label: names.get(a.key) ?? a.key, count: a.count }))} />
              </Panel>
            </div>
            <Panel
              title="Changes"
              note={data.entries.length < data.total ? `latest ${data.entries.length} of ${data.total}` : undefined}
              flush
            >
              {data.entries.length === 0 ? (
                <p className="empty">Nothing recorded for these filters.</p>
              ) : (
                <div className="grid-wrap">
                  <table className="grid" aria-label="Audit entries">
                    <thead>
                      <tr>
                        <th>When</th>
                        <th>What</th>
                        <th>Who</th>
                        <th>Object</th>
                        <th>Details</th>
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
                                {String(e.details.number ?? "Object")}
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
  if (d.previousNumber != null) parts.push(`was ${String(d.previousNumber)} “${String(d.previousName)}”`);
  if (d.principalId != null && Array.isArray(d.permissions))
    parts.push(`shared with ${String(d.principalId)} (${(d.permissions as string[]).join(", ").toLowerCase()})`);
  if (d.comment) parts.push(`“${String(d.comment)}”`);
  if (d.name != null && d.previousNumber == null && d.number != null) parts.push(String(d.name));
  return parts.join(" · ");
}
