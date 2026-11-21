import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Flag, Plus, Trash2 } from "lucide-react";
import { useMemo, useState } from "react";
import { Link } from "react-router";
import { api, unwrap, type Schemas } from "../api/client";
import { useHistory, type VersionedObject } from "../api/queries";
import { ErrorBanner, Loading, Panel, PrincipalName, formatDate } from "../components/common";
import { t } from "../i18n/i18n";
import { effectivityKindLabel, useEffectivityContexts, type EffectivityKind } from "../structure/StructureTree";

type Effectivity = Schemas["Effectivity"];

const KINDS: EffectivityKind[] = ["DATE", "SERIAL", "LOT", "BLOCK"];

/** "1 – 99", "100 –", "2027-07-01 – 2027-12-31". */
export const rangeText = (e: { from: string; to?: string | null }) => (e.to ? `${e.from} – ${e.to}` : `${e.from} –`);

export const useEffectivity = (partId: string, workingCopy = false) =>
  useQuery({
    queryKey: ["effectivity", partId, workingCopy],
    queryFn: () => unwrap(api.GET("/api/v1/objects/{id}/effectivity", { params: { path: { id: partId }, query: { workingCopy } } })),
  });

/** The fields of a new range: by what, from, to, of which end item. */
function RangeForm({
  onAdd,
  busy,
  children,
}: {
  onAdd: (r: { kind: EffectivityKind; from: string; to?: string; endItemId?: string }) => void;
  busy: boolean;
  children?: React.ReactNode;
}) {
  const { data: contexts } = useEffectivityContexts();
  const [kind, setKind] = useState<EffectivityKind>("SERIAL");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [endItem, setEndItem] = useState("");
  const needsItem = kind !== "DATE";
  const item = endItem || contexts?.[0]?.endItem.id || "";
  const type = kind === "DATE" ? "date" : "text";
  return (
    <div className="row wrap eff-form">
      {children}
      <label className="field">
        <span>{t("Effective by")}</span>
        <select value={kind} onChange={(e) => setKind(e.target.value as EffectivityKind)}>
          {KINDS.map((k) => (
            <option key={k} value={k}>
              {effectivityKindLabel(k)}
            </option>
          ))}
        </select>
      </label>
      <label className="field">
        <span>{t("From")}</span>
        <input type={type} value={from} onChange={(e) => setFrom(e.target.value)} placeholder={kind === "LOT" ? "L-2027-01" : "1"} />
      </label>
      <label className="field">
        <span>{t("To (empty: open)")}</span>
        <input type={type} value={to} onChange={(e) => setTo(e.target.value)} />
      </label>
      <label className="field">
        <span>{t("Of end item")}</span>
        <select value={needsItem ? item : endItem} onChange={(e) => setEndItem(e.target.value)}>
          {!needsItem && <option value="">{t("Any end item")}</option>}
          {contexts?.map((c) => (
            <option key={c.id} value={c.endItem.id}>
              {c.endItem.number} {c.endItem.name}
            </option>
          ))}
        </select>
      </label>
      <button
        className="btn primary"
        disabled={busy || !from.trim() || (needsItem && !item)}
        onClick={() => onAdd({ kind, from: from.trim(), to: to.trim() || undefined, endItemId: (needsItem ? item : endItem) || undefined })}
      >
        <Plus size={14} aria-hidden /> {t("Add")}
      </button>
    </div>
  );
}

function RangeTable({
  rows,
  label,
  canEdit,
  onDelete,
}: {
  rows: Effectivity[];
  label: string;
  canEdit: boolean;
  onDelete: (id: string) => void;
}) {
  if (!rows.length) return <div className="empty small">{t("None yet: it applies everywhere.")}</div>;
  return (
    <table className="grid" aria-label={label}>
      <thead>
        <tr>
          <th>{rows[0]!.target === "VERSION" ? t("Revision") : t("Line")}</th>
          <th>{t("Effective by")}</th>
          <th>{t("Range")}</th>
          <th>{t("End item")}</th>
          <th>{t("Status")}</th>
          <th>{t("Set")}</th>
          <th />
        </tr>
      </thead>
      <tbody>
        {rows.map((e) => (
          <tr key={e.id}>
            <td className="nowrap">
              {e.target === "VERSION" ? (
                <strong>{e.revision}</strong>
              ) : (
                <>
                  {e.lineNumber} · {e.lineChild?.number} {e.lineChild?.name}
                </>
              )}
            </td>
            <td>{effectivityKindLabel(e.kind)}</td>
            <td className="nowrap num-text">{rangeText(e)}</td>
            <td>
              {e.context ? `${e.context.endItem.number ?? ""} ${e.context.endItem.name ?? ""}` : <span className="muted">{t("any")}</span>}
            </td>
            <td>
              {e.status === "PENDING" ? (
                <span className="tag warn">
                  {t("Pending")}
                  {e.changeId && (
                    <>
                      {" · "}
                      <Link to={`/objects/${e.changeId}`}>{t("change")}</Link>
                    </>
                  )}
                </span>
              ) : (
                <span className="tag ok">
                  {t("In effect")}
                  {e.changeId && (
                    <>
                      {" · "}
                      <Link to={`/objects/${e.changeId}?tab=change`}>{t("change")}</Link>
                    </>
                  )}
                </span>
              )}
            </td>
            <td className="muted small nowrap">
              <PrincipalName id={e.createdBy} /> · {formatDate(e.createdAt)}
            </td>
            <td>
              {canEdit && e.status === "ACTUAL" && (
                <button className="icon-btn" aria-label={t("Remove")} onClick={() => onDelete(e.id)}>
                  <Trash2 size={13} />
                </button>
              )}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/**
 * Effectivity of a part (roadmap 7.1, 7.4): whether it's an end item, when each of its revisions
 * applies, and when the lines of its structure do (edited on the working copy).
 */
export function EffectivityTab({ obj, mine }: { obj: VersionedObject; mine: boolean }) {
  const qc = useQueryClient();
  const { data: rows, error } = useEffectivity(obj.id, mine);
  const { data: contexts } = useEffectivityContexts();
  const { data: history } = useHistory(obj.id);
  const { data: lines } = useQuery({
    queryKey: ["usages", obj.id, mine],
    queryFn: () => unwrap(api.GET("/api/v1/objects/{id}/usages", { params: { path: { id: obj.id }, query: { workingCopy: mine } } })),
  });
  const revisions = useMemo(() => [...new Set((history ?? []).map((i) => i.revision))], [history]);
  const [revision, setRevision] = useState("");
  const [lineId, setLineId] = useState("");
  const context = contexts?.find((c) => c.endItem.id === obj.id);
  const refresh = () => {
    void qc.invalidateQueries({ queryKey: ["effectivity", obj.id] });
    void qc.invalidateQueries({ queryKey: ["effectivityContexts"] });
    void qc.invalidateQueries({ queryKey: ["structure"] });
  };
  const add = useMutation({
    mutationFn: (body: Schemas["AddEffectivity"]) =>
      unwrap(api.POST("/api/v1/objects/{id}/effectivity", { params: { path: { id: obj.id } }, body })),
    onSuccess: refresh,
  });
  const remove = useMutation({
    mutationFn: (id: string) => unwrap(api.DELETE("/api/v1/effectivity/{effectivityId}", { params: { path: { effectivityId: id } } })),
    onSuccess: refresh,
  });
  const makeEndItem = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/effectivity-contexts", { body: { endItemId: obj.id } })),
    onSuccess: refresh,
  });
  const dropEndItem = useMutation({
    mutationFn: (id: string) => unwrap(api.DELETE("/api/v1/effectivity-contexts/{id}", { params: { path: { id } } })),
    onSuccess: refresh,
  });
  if (!rows) return error ? <ErrorBanner error={error} /> : <Loading />;
  const versionRows = rows.filter((r) => r.target === "VERSION");
  const lineRows = rows.filter((r) => r.target === "LINE");
  const rev = revision || [...revisions].sort().at(-1) || "";
  return (
    <>
      <ErrorBanner error={add.error ?? remove.error ?? makeEndItem.error ?? dropEndItem.error} />
      <Panel
        title={t("End item")}
        note={t("Serial numbers, lots and blocks count in an end item: the product as it's built and delivered.")}
        actions={
          context ? (
            <button className="btn small ghost" onClick={() => dropEndItem.mutate(context.id)}>
              {t("Stop being an end item")}
            </button>
          ) : (
            <button className="btn small" onClick={() => makeEndItem.mutate()} disabled={makeEndItem.isPending}>
              <Flag size={13} aria-hidden /> {t("Make it an end item")}
            </button>
          )
        }
      >
        <p className="muted small" style={{ margin: 0 }}>
          {context
            ? t("{number} is an end item: effectivity can be given in its serial numbers, lots and blocks.", { number: obj.number })
            : t("{number} isn't an end item.", { number: obj.number })}
        </p>
      </Panel>
      <Panel title={t("Versions")} note={t("When each revision of {number} is the one to build.", { number: obj.number })} flush>
        <RangeTable rows={versionRows} label={t("Version effectivity")} canEdit onDelete={(id) => remove.mutate(id)} />
        <RangeForm
          busy={add.isPending}
          onAdd={(r) => add.mutate({ target: "VERSION", revision: rev, kind: r.kind, from: r.from, to: r.to, endItemId: r.endItemId })}
        >
          <label className="field">
            <span>{t("Revision")}</span>
            <select value={rev} onChange={(e) => setRevision(e.target.value)}>
              {revisions.map((r) => (
                <option key={r}>{r}</option>
              ))}
            </select>
          </label>
        </RangeForm>
      </Panel>
      {(lines?.length ?? 0) > 0 && (
        <Panel
          title={t("Lines")}
          note={
            mine ? t("On your working copy: kept with the lines when you check in.") : t("Check it out to change when its lines apply.")
          }
          flush
        >
          <RangeTable rows={lineRows} label={t("Line effectivity")} canEdit={mine} onDelete={(id) => remove.mutate(id)} />
          {mine && (
            <RangeForm
              busy={add.isPending}
              onAdd={(r) =>
                add.mutate({
                  target: "LINE",
                  lineId: lineId || lines![0]!.id,
                  kind: r.kind,
                  from: r.from,
                  to: r.to,
                  endItemId: r.endItemId,
                })
              }
            >
              <label className="field">
                <span>{t("Line")}</span>
                <select value={lineId || lines![0]!.id} onChange={(e) => setLineId(e.target.value)}>
                  {lines!.map((l) => (
                    <option key={l.id} value={l.id}>
                      {l.lineNumber} · {l.child.number} {l.child.name}
                    </option>
                  ))}
                </select>
              </label>
            </RangeForm>
          )}
        </Panel>
      )}
    </>
  );
}

/**
 * The effectivity a change notice gives its results (roadmap 7.3): pending until the notice is
 * resolved, then in effect, the range each supersedes closed just before it.
 */
export function ChangeEffectivityPanel({ notice, results }: { notice: VersionedObject; results: Schemas["LinkedObject"][] }) {
  const qc = useQueryClient();
  const parts = results.filter((r) => r.readable && r.version);
  const [target, setTarget] = useState("");
  const chosen = parts.find((p) => p.id === target) ?? parts[0];
  const key = ["changeEffectivity", notice.id];
  const { data: rows } = useQuery({
    queryKey: key,
    queryFn: () => unwrap(api.GET("/api/v1/changes/{id}/effectivity", { params: { path: { id: notice.id } } })),
  });
  const refresh = () => {
    void qc.invalidateQueries({ queryKey: key });
    void qc.invalidateQueries({ queryKey: ["effectivity"] });
  };
  const add = useMutation({
    mutationFn: (r: { kind: EffectivityKind; from: string; to?: string; endItemId?: string }) =>
      unwrap(
        api.POST("/api/v1/objects/{id}/effectivity", {
          params: { path: { id: chosen!.id } },
          body: { target: "VERSION", revision: chosen!.version!.split(".")[0], changeId: notice.id, ...r },
        }),
      ),
    onSuccess: refresh,
  });
  const remove = useMutation({
    mutationFn: (id: string) => unwrap(api.DELETE("/api/v1/effectivity/{effectivityId}", { params: { path: { effectivityId: id } } })),
    onSuccess: refresh,
  });
  const open = !["RESOLVED", "CANCELLED"].includes(notice.latest.state);
  const byPart = new Map(results.map((r) => [r.id, r]));
  return (
    <Panel
      title={t("Effectivity of the results")}
      note={
        open
          ? t("Pending until this notice is resolved; then it takes effect, and the range it supersedes ends just before it.")
          : t("In effect since this notice was resolved.")
      }
      flush
    >
      <ErrorBanner error={add.error ?? remove.error} />
      {rows && rows.length > 0 ? (
        <table className="grid" aria-label={t("Effectivity of the results")}>
          <thead>
            <tr>
              <th>{t("Part")}</th>
              <th>{t("Revision")}</th>
              <th>{t("Effective by")}</th>
              <th>{t("Range")}</th>
              <th>{t("End item")}</th>
              <th>{t("Status")}</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {rows.map((e) => (
              <tr key={e.id}>
                <td>
                  <Link to={`/objects/${e.partId}?tab=effectivity`}>
                    {byPart.get(e.partId)?.number} {byPart.get(e.partId)?.name}
                  </Link>
                </td>
                <td>
                  <strong>{e.revision}</strong>
                </td>
                <td>{effectivityKindLabel(e.kind)}</td>
                <td className="nowrap num-text">{rangeText(e)}</td>
                <td>
                  {e.context ? (
                    `${e.context.endItem.number ?? ""} ${e.context.endItem.name ?? ""}`
                  ) : (
                    <span className="muted">{t("any")}</span>
                  )}
                </td>
                <td>
                  {e.status === "PENDING" ? (
                    <span className="tag warn">{t("Pending")}</span>
                  ) : (
                    <span className="tag ok">{t("In effect")}</span>
                  )}
                </td>
                <td>
                  {open && e.status === "PENDING" && (
                    <button className="icon-btn" aria-label={t("Remove")} onClick={() => remove.mutate(e.id)}>
                      <Trash2 size={13} />
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <div className="empty small">{parts.length ? t("No effectivity set yet.") : t("Add resulting parts first.")}</div>
      )}
      {open && chosen && (
        <RangeForm busy={add.isPending} onAdd={(r) => add.mutate(r)}>
          <label className="field">
            <span>{t("Part")}</span>
            <select value={chosen.id} onChange={(e) => setTarget(e.target.value)}>
              {parts.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.number} {p.name} · {p.version?.split(".")[0]}
                </option>
              ))}
            </select>
          </label>
        </RangeForm>
      )}
    </Panel>
  );
}
