import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Archive, Eye, Gavel, Play, Plus, RotateCcw, Trash2 } from "lucide-react";
import { useState } from "react";
import { Link } from "react-router";
import { api, unwrap } from "../api/client";
import { useContextsOfKind, useTypes } from "../api/queries";
import { Dialog, ErrorBanner, Loading, Panel, PrincipalName, When, typeLabel } from "../components/common";
import { JobProgress, JobStatusTag } from "../components/JobProgress";
import { t, tn } from "../i18n/i18n";

const ACTIONS = (): Record<string, string> => ({
  PURGE_ITERATIONS: t("Purge old iterations"),
  ARCHIVE: t("Archive"),
  SKIP: t("Skipped"),
});

/** A banner on an object that a legal hold freezes (roadmap 9.8). */
export function LegalHoldBanner({ objectId }: { objectId: string }) {
  const { data } = useQuery({
    queryKey: ["legalHolds", objectId],
    queryFn: () => unwrap(api.GET("/api/v1/objects/{id}/legal-holds", { params: { path: { id: objectId } } })),
  });
  if (!data?.length) return null;
  return (
    <div className="banner warn hold-banner">
      <Gavel size={15} aria-hidden /> {t("On legal hold: it can't be deleted, purged or archived.")}{" "}
      <strong>{data.map((h) => h.name).join(", ")}</strong>
    </div>
  );
}

/** Administration → Retention: rules, legal holds, the dry run, runs and archives. */
export function RetentionAdmin() {
  return (
    <div className="stack">
      <Rules />
      <Holds />
      <DryRun />
      <Archives />
    </div>
  );
}

function Rules() {
  const qc = useQueryClient();
  const { data: rules, isLoading } = useQuery({ queryKey: ["retentionRules"], queryFn: () => unwrap(api.GET("/api/v1/retention/rules")) });
  const { data: types } = useTypes();
  const [name, setName] = useState("");
  const [typeId, setTypeId] = useState("00000000-0000-7000-8000-000000000101");
  const [state, setState] = useState("");
  const [years, setYears] = useState(10);
  const [action, setAction] = useState("PURGE_ITERATIONS");
  const refresh = () => void qc.invalidateQueries({ queryKey: ["retentionRules"] });
  const add = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/api/v1/retention/rules", {
          body: { name, typeId, state: state || null, ageDays: Math.round(years * 365), action, active: true },
        }),
      ),
    onSuccess: () => {
      setName("");
      refresh();
    },
  });
  const remove = useMutation({
    mutationFn: (id: string) => unwrap(api.DELETE("/api/v1/retention/rules/{id}", { params: { path: { id } } })),
    onSuccess: refresh,
  });
  const typeName = (id: string) => {
    const ty = types?.find((x) => x.id === id);
    return ty ? typeLabel(ty) : id;
  };
  if (isLoading) return <Loading />;
  return (
    <Panel
      title={t("Retention rules")}
      note={t("After so long since an object's latest check-in: purge its old iterations, or archive it")}
      flush
    >
      <ErrorBanner error={add.error ?? remove.error} />
      <table className="grid" aria-label={t("Retention rules")}>
        <thead>
          <tr>
            <th>{t("Rule")}</th>
            <th>{t("Type")}</th>
            <th>{t("State")}</th>
            <th>{t("After")}</th>
            <th>{t("Action")}</th>
            <th aria-label={t("Actions")} />
          </tr>
        </thead>
        <tbody>
          {rules?.map((r) => (
            <tr key={r.id}>
              <td>
                <strong>{r.name}</strong>
              </td>
              <td>{typeName(r.typeId)}</td>
              <td>{r.state ?? <span className="muted">{t("any")}</span>}</td>
              <td>{r.ageDays >= 365 ? tn("{n} year", "{n} years", Math.round(r.ageDays / 365)) : tn("{n} day", "{n} days", r.ageDays)}</td>
              <td>
                <span className={r.action === "ARCHIVE" ? "tag warn" : "tag"}>{ACTIONS()[r.action]}</span>
              </td>
              <td style={{ width: 40 }}>
                <button className="icon-btn" aria-label={t("Delete")} onClick={() => remove.mutate(r.id)}>
                  <Trash2 size={13} />
                </button>
              </td>
            </tr>
          ))}
          <tr>
            <td>
              <input
                aria-label={t("Rule name")}
                placeholder={t("e.g. Test reports: 10 years")}
                value={name}
                onChange={(e) => setName(e.target.value)}
              />
            </td>
            <td>
              <select aria-label={t("Type")} value={typeId} onChange={(e) => setTypeId(e.target.value)}>
                {types?.map((ty) => (
                  <option key={ty.id} value={ty.id}>
                    {typeLabel(ty)}
                  </option>
                ))}
              </select>
            </td>
            <td>
              <input
                aria-label={t("State")}
                placeholder={t("any")}
                value={state}
                onChange={(e) => setState(e.target.value.toUpperCase())}
              />
            </td>
            <td>
              <input
                type="number"
                min={0}
                step={0.5}
                aria-label={t("Years")}
                value={years}
                onChange={(e) => setYears(Number(e.target.value))}
                style={{ width: 80 }}
              />{" "}
              {t("years")}
            </td>
            <td>
              <select aria-label={t("Action")} value={action} onChange={(e) => setAction(e.target.value)}>
                <option value="PURGE_ITERATIONS">{ACTIONS().PURGE_ITERATIONS}</option>
                <option value="ARCHIVE">{ACTIONS().ARCHIVE}</option>
              </select>
            </td>
            <td>
              <button className="btn small" disabled={!name.trim() || add.isPending} onClick={() => add.mutate()}>
                <Plus size={13} aria-hidden /> {t("Add")}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </Panel>
  );
}

function Holds() {
  const qc = useQueryClient();
  const { data: holds } = useQuery({ queryKey: ["legalHolds"], queryFn: () => unwrap(api.GET("/api/v1/legal-holds")) });
  const { data: products } = useContextsOfKind("PRODUCT");
  const [creating, setCreating] = useState(false);
  const release = useMutation({
    mutationFn: (id: string) => unwrap(api.POST("/api/v1/legal-holds/{id}/release", { params: { path: { id } } })),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["legalHolds"] }),
  });
  return (
    <Panel
      title={t("Legal holds")}
      note={t("Frozen against deletion, purge and archive until released")}
      actions={
        <button className="btn small" onClick={() => setCreating(true)}>
          <Gavel size={13} aria-hidden /> {t("New legal hold")}
        </button>
      }
      flush
    >
      <ErrorBanner error={release.error} />
      {!holds?.length ? (
        <p className="empty">{t("No legal holds.")}</p>
      ) : (
        <table className="grid" aria-label={t("Legal holds")}>
          <thead>
            <tr>
              <th>{t("Hold")}</th>
              <th>{t("Holds")}</th>
              <th>{t("Since")}</th>
              <th>{t("Status")}</th>
              <th aria-label={t("Actions")} />
            </tr>
          </thead>
          <tbody>
            {holds.map((h) => (
              <tr key={h.id}>
                <td>
                  <strong>{h.name}</strong>
                  {h.reason && <div className="muted small">{h.reason}</div>}
                </td>
                <td className="small">
                  {[
                    ...h.contextIds.map((c) => products?.find((p) => p.id === c)?.name ?? c),
                    ...(h.objectIds.length ? [tn("{n} object", "{n} objects", h.objectIds.length)] : []),
                  ].join(", ")}
                </td>
                <td className="small">
                  <When iso={h.createdAt} /> · <PrincipalName id={h.createdBy} />
                </td>
                <td>
                  {h.releasedAt ? (
                    <span className="tag">
                      {t("released")} <When iso={h.releasedAt} />
                    </span>
                  ) : (
                    <span className="tag warn">{t("in force")}</span>
                  )}
                </td>
                <td>
                  {!h.releasedAt && (
                    <button className="btn small" onClick={() => release.mutate(h.id)}>
                      {t("Release")}
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {creating && <HoldDialog onClose={() => setCreating(false)} />}
    </Panel>
  );
}

function HoldDialog({ onClose }: { onClose: () => void }) {
  const qc = useQueryClient();
  const { data: products } = useContextsOfKind("PRODUCT");
  const [name, setName] = useState("");
  const [reason, setReason] = useState("");
  const [contextId, setContextId] = useState("");
  const save = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/api/v1/legal-holds", {
          body: { name, reason: reason || null, contextIds: [contextId || products![0]!.id], objectIds: [] },
        }),
      ),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["legalHolds"] });
      onClose();
    },
  });
  return (
    <Dialog
      open
      title={t("New legal hold")}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            {t("Cancel")}
          </button>
          <button className="btn primary" disabled={!name.trim() || save.isPending} onClick={() => save.mutate()}>
            {t("Place the hold")}
          </button>
        </>
      }
    >
      <ErrorBanner error={save.error} />
      <div className="field">
        <label htmlFor="lh-name">{t("Name")}</label>
        <input id="lh-name" value={name} onChange={(e) => setName(e.target.value)} placeholder={t("e.g. Case 2026-118")} />
      </div>
      <div className="field">
        <label htmlFor="lh-reason">{t("Reason")}</label>
        <input id="lh-reason" value={reason} onChange={(e) => setReason(e.target.value)} />
      </div>
      <div className="field">
        <label htmlFor="lh-context">{t("Everything in the product")}</label>
        <select id="lh-context" value={contextId} onChange={(e) => setContextId(e.target.value)}>
          {products?.map((p) => (
            <option key={p.id} value={p.id}>
              {p.name}
            </option>
          ))}
        </select>
      </div>
    </Dialog>
  );
}

function DryRun() {
  const qc = useQueryClient();
  const [asked, setAsked] = useState(false);
  const preview = useQuery({
    queryKey: ["retentionPreview"],
    queryFn: () => unwrap(api.GET("/api/v1/retention/preview")),
    enabled: asked,
  });
  const run = useMutation({ mutationFn: () => unwrap(api.POST("/api/v1/retention/run")) });
  const { data: job } = useQuery({
    queryKey: ["jobs", run.data?.id],
    queryFn: () => unwrap(api.GET("/api/v1/jobs/{id}", { params: { path: { id: run.data!.id } } })),
    enabled: !!run.data,
    refetchInterval: (q) => {
      const s = q.state.data?.status;
      if (s && s !== "QUEUED" && s !== "RUNNING") {
        void qc.invalidateQueries({ queryKey: ["archives"] });
        return false;
      }
      return 1000;
    },
  });
  const rows = preview.data ?? [];
  return (
    <Panel
      title={t("Dry run")}
      note={t("What the rules would do now, object by object")}
      actions={
        <>
          {job && <JobStatusTag job={job} />}
          <button
            className="btn small"
            onClick={() => {
              setAsked(true);
              void preview.refetch();
            }}
          >
            <Eye size={13} aria-hidden /> {t("Check")}
          </button>
          <button
            className="btn small primary"
            disabled={!rows.some((r) => r.action !== "SKIP") || run.isPending}
            onClick={() => run.mutate()}
          >
            <Play size={13} aria-hidden /> {t("Run now")}
          </button>
        </>
      }
      flush
    >
      <ErrorBanner error={preview.error ?? run.error} />
      {job && (job.status === "RUNNING" || job.status === "QUEUED") && <JobProgress job={job} />}
      {preview.isFetching ? (
        <Loading />
      ) : !asked ? (
        <p className="empty">{t("Check what the rules would do before running them.")}</p>
      ) : rows.length === 0 ? (
        <p className="empty">{t("Nothing to do.")}</p>
      ) : (
        <table className="grid" aria-label={t("Dry run")}>
          <thead>
            <tr>
              <th>{t("Object")}</th>
              <th>{t("Rule")}</th>
              <th>{t("Action")}</th>
              <th>{t("Detail")}</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.objectId}>
                <td>
                  <Link to={`/objects/${r.objectId}`}>{r.number}</Link> {r.name} <span className="version">{r.version}</span>
                </td>
                <td className="small">{r.rule}</td>
                <td>
                  <span className={r.action === "SKIP" ? "tag" : r.action === "ARCHIVE" ? "tag warn" : "tag accent"}>
                    {ACTIONS()[r.action]}
                  </span>
                </td>
                <td className="small">{r.detail}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Panel>
  );
}

function Archives() {
  const qc = useQueryClient();
  const { data } = useQuery({ queryKey: ["archives"], queryFn: () => unwrap(api.GET("/api/v1/archives")) });
  const restore = useMutation({
    mutationFn: (id: string) => unwrap(api.POST("/api/v1/archives/{id}/restore", { params: { path: { id } } })),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["archives"] }),
  });
  return (
    <Panel
      title={t("Archives")}
      note={t("Objects taken out of the system, each with its whole history; restore brings it back as it was")}
      flush
    >
      <ErrorBanner error={restore.error} />
      {!data?.length ? (
        <p className="empty">{t("Nothing archived yet.")}</p>
      ) : (
        <table className="grid" aria-label={t("Archives")}>
          <thead>
            <tr>
              <th>{t("Object")}</th>
              <th className="num">{t("Iterations")}</th>
              <th className="num">{t("Files")}</th>
              <th>{t("Archived")}</th>
              <th aria-label={t("Actions")} />
            </tr>
          </thead>
          <tbody>
            {data.map((a) => (
              <tr key={a.id}>
                <td>
                  <Archive size={13} aria-hidden className="muted" /> <strong>{a.number}</strong> {a.name}
                </td>
                <td className="num">{a.iterations}</td>
                <td className="num">{a.files}</td>
                <td className="small">
                  <When iso={a.archivedAt} />
                </td>
                <td>
                  {a.restoredAt ? (
                    <span className="tag allow">
                      {t("restored")} <When iso={a.restoredAt} />
                    </span>
                  ) : (
                    <button className="btn small" onClick={() => restore.mutate(a.id)} disabled={restore.isPending}>
                      <RotateCcw size={12} aria-hidden /> {t("Restore")}
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Panel>
  );
}
