import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowRightLeft, ClipboardPlus, FolderInput, GitBranchPlus, PencilLine, Square, Trash2, X } from "lucide-react";
import { useState } from "react";
import { Link } from "react-router";
import { api, unwrap, type Schemas } from "../api/client";
import { useEnumerations, useLifecycles, useTypeAttributes, type VersionedObject } from "../api/queries";
import { pickLabel } from "../components/AttributeForm";
import { useClipboard } from "../components/Clipboard";
import { Dialog, ErrorBanner, Panel } from "../components/common";
import { JobProgress, JobStatusTag } from "../components/JobProgress";
import { t, tn } from "../i18n/i18n";
import { FolderPicker } from "./Folders";

type Operation = Schemas["BulkRequest"]["operation"];
type Result = { objectId: string; number?: string | null; ok: boolean; message: string };

/**
 * Mass operations (roadmap 3.5): what's ticked in the table, changed in one go by a background
 * job. Each object succeeds or fails on its own; the results stay on screen.
 */
export function BulkBar({
  objects,
  contextId,
  folderId,
  onClear,
  onStarted,
}: {
  objects: VersionedObject[];
  contextId: string;
  folderId?: string;
  onClear: () => void;
  onStarted: (jobId: string) => void;
}) {
  const [dialog, setDialog] = useState<Operation | null>(null);
  const clipboard = useClipboard();
  return (
    <div className="bulk-bar" role="toolbar" aria-label={t("Selected objects")}>
      <strong>{t("{count} selected", { count: objects.length })}</strong>
      <button className="btn small" onClick={() => setDialog("SET_STATE")}>
        <ArrowRightLeft size={13} aria-hidden /> {t("Set state…")}
      </button>
      <button className="btn small" onClick={() => setDialog("SET_ATTRIBUTE")}>
        <PencilLine size={13} aria-hidden /> {t("Change a value…")}
      </button>
      <button className="btn small" onClick={() => setDialog("REVISE")}>
        <GitBranchPlus size={13} aria-hidden /> {t("Revise…")}
      </button>
      <button className="btn small" onClick={() => setDialog("MOVE")}>
        <FolderInput size={13} aria-hidden /> {t("Move…")}
      </button>
      <button
        className="btn small"
        onClick={() => objects.forEach((o) => clipboard.add({ id: o.id, number: o.number, name: o.name, version: o.version }))}
      >
        <ClipboardPlus size={13} aria-hidden /> {t("Add to clipboard")}
      </button>
      <button className="btn small ghost danger" onClick={() => setDialog("DELETE")}>
        <Trash2 size={13} aria-hidden /> {t("Delete…")}
      </button>
      <span className="grow" />
      <button className="icon-btn" aria-label={t("Clear the selection")} onClick={onClear}>
        <X size={14} />
      </button>
      {dialog && (
        <BulkDialog
          operation={dialog}
          objects={objects}
          contextId={contextId}
          folderId={folderId}
          onClose={() => setDialog(null)}
          onStarted={(id) => {
            setDialog(null);
            onClear();
            onStarted(id);
          }}
        />
      )}
    </div>
  );
}

/** "Move 3 objects" */
function bulkTitle(operation: Operation, n: number): string {
  switch (operation) {
    case "SET_STATE":
      return tn("Set the state of {n} object", "Set the state of {n} objects", n);
    case "SET_ATTRIBUTE":
      return tn("Change a value of {n} object", "Change a value of {n} objects", n);
    case "MOVE":
      return tn("Move {n} object", "Move {n} objects", n);
    case "DELETE":
      return tn("Delete {n} object", "Delete {n} objects", n);
    case "REVISE":
      return tn("Revise {n} object", "Revise {n} objects", n);
  }
  return `${operation} ${n}`;
}

function BulkDialog({
  operation,
  objects,
  contextId,
  folderId,
  onClose,
  onStarted,
}: {
  operation: Operation;
  objects: VersionedObject[];
  contextId: string;
  folderId?: string;
  onClose: () => void;
  onStarted: (jobId: string) => void;
}) {
  const { data: lifecycles } = useLifecycles();
  const { data: definitions } = useTypeAttributes(objects[0]?.typeId);
  const { data: enumerations } = useEnumerations();
  const [state, setState] = useState("");
  const [comment, setComment] = useState("");
  const [attribute, setAttribute] = useState("");
  const [value, setValue] = useState("");
  const [target, setTarget] = useState<{ contextId: string; folderId?: string }>({ contextId, folderId });
  const states = new Map<string, string>();
  for (const lc of lifecycles ?? []) for (const s of lc.states) if (!states.has(s.key)) states.set(s.key, pickLabel(s.labels, s.key));
  const editable = (definitions ?? []).filter(
    (d) => !d.formula && ["STRING", "TEXT", "INTEGER", "REAL", "BOOLEAN", "ENUMERATION", "URL", "DATE"].includes(d.dataType),
  );
  const chosen = editable.find((d) => d.name === attribute);
  const typed = (): unknown => {
    if (!chosen) return value;
    if (chosen.dataType === "INTEGER") return value === "" ? null : Number.parseInt(value, 10);
    if (chosen.dataType === "REAL") return value === "" ? null : Number(value);
    if (chosen.dataType === "BOOLEAN") return value === "true";
    return value === "" ? null : value;
  };
  const start = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/api/v1/bulk", {
          body: {
            operation,
            objectIds: objects.map((o) => o.id),
            state: state || null,
            comment: comment || null,
            contextId: operation === "MOVE" ? target.contextId : null,
            folderId: operation === "MOVE" ? (target.folderId ?? null) : null,
            attribute: attribute || null,
            value: operation === "SET_ATTRIBUTE" ? typed() : null,
          },
        }),
      ),
    onSuccess: (job) => onStarted(job.id),
  });
  const ready =
    operation === "SET_STATE" ? !!state : operation === "SET_ATTRIBUTE" ? !!attribute : operation === "MOVE" ? !!target.contextId : true;

  return (
    <Dialog
      open
      title={bulkTitle(operation, objects.length)}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            {t("Cancel")}
          </button>
          <button
            className={operation === "DELETE" ? "btn danger-fill" : "btn primary"}
            disabled={!ready || start.isPending}
            onClick={() => start.mutate()}
          >
            {operation === "DELETE" ? t("Delete them") : t("Start")}
          </button>
        </>
      }
    >
      <div className="attr-form">
        <ErrorBanner error={start.error} />
        <p className="muted wide" style={{ margin: 0 }}>
          {t(
            "Runs in the background, object by object, with your access rights. Objects that can't be changed are listed with the reason; the others go ahead.",
          )}
        </p>
        {operation === "SET_STATE" && (
          <>
            <div className="field">
              <label htmlFor="bulk-state">{t("New state")}</label>
              <select id="bulk-state" value={state} onChange={(e) => setState(e.target.value)}>
                <option value="">{t("Choose…")}</option>
                {[...states.entries()].map(([k, l]) => (
                  <option key={k} value={k}>
                    {l}
                  </option>
                ))}
              </select>
            </div>
            <div className="field">
              <label htmlFor="bulk-comment">{t("Comment")}</label>
              <input id="bulk-comment" value={comment} onChange={(e) => setComment(e.target.value)} />
            </div>
          </>
        )}
        {operation === "SET_ATTRIBUTE" && (
          <>
            <div className="field">
              <label htmlFor="bulk-attr">{t("Attribute")}</label>
              <select id="bulk-attr" value={attribute} onChange={(e) => (setAttribute(e.target.value), setValue(""))}>
                <option value="">{t("Choose…")}</option>
                {editable.map((d) => (
                  <option key={d.name} value={d.name}>
                    {pickLabel(d.labels, d.name)}
                  </option>
                ))}
              </select>
            </div>
            <div className="field">
              <label htmlFor="bulk-value">{t("New value")}</label>
              {chosen?.dataType === "ENUMERATION" && chosen.enumerationId ? (
                <select id="bulk-value" value={value} onChange={(e) => setValue(e.target.value)}>
                  <option value="">{t("(empty)")}</option>
                  {enumerations?.get(chosen.enumerationId)?.values.map((en) => (
                    <option key={en.key} value={en.key}>
                      {pickLabel(en.labels, en.key)}
                    </option>
                  ))}
                </select>
              ) : chosen?.dataType === "BOOLEAN" ? (
                <select id="bulk-value" value={value} onChange={(e) => setValue(e.target.value)}>
                  <option value="false">{t("No")}</option>
                  <option value="true">{t("Yes")}</option>
                </select>
              ) : (
                <input id="bulk-value" value={value} onChange={(e) => setValue(e.target.value)} />
              )}
            </div>
            <span className="hint wide">{t("Each object is checked out, changed and checked in as a new iteration.")}</span>
          </>
        )}
        {operation === "MOVE" && (
          <FolderPicker
            contextId={target.contextId}
            folderId={target.folderId}
            onChange={(c, f) => setTarget({ contextId: c, folderId: f })}
          />
        )}
        {operation === "REVISE" && (
          <div className="banner info wide">
            {t(
              "Each object gets a new revision, starting from its latest version; those that can't be revised in their state are reported.",
            )}
          </div>
        )}
        {operation === "DELETE" && (
          <div className="banner warn wide">
            {t("Each object and all its versions and files are removed, where the delete rules allow. This can't be undone.")}
          </div>
        )}
      </div>
    </Dialog>
  );
}

/** A mass operation as it runs, then its outcome object by object. */
export function BulkProgress({ jobId, onClose }: { jobId: string; onClose: () => void }) {
  const qc = useQueryClient();
  const { data: job } = useQuery({
    queryKey: ["jobs", jobId],
    queryFn: () => unwrap(api.GET("/api/v1/jobs/{id}", { params: { path: { id: jobId } } })),
    refetchInterval: (q) => {
      const s = q.state.data?.status;
      if (s && s !== "QUEUED" && s !== "RUNNING") {
        void qc.invalidateQueries({ queryKey: ["objects"] });
        void qc.invalidateQueries({ queryKey: ["folders"] });
        void qc.invalidateQueries({ queryKey: ["structure"] });
        void qc.invalidateQueries({ queryKey: ["object"] });
        void qc.invalidateQueries({ queryKey: ["whereUsed"] });
        void qc.invalidateQueries({ queryKey: ["usages"] });
        return false;
      }
      return 1000;
    },
  });
  const cancel = useMutation({ mutationFn: () => unwrap(api.POST("/api/v1/jobs/{id}/cancel", { params: { path: { id: jobId } } })) });
  if (!job) return null;
  const results = ((job.result?.results as Result[] | undefined) ?? []).slice().sort((a, b) => Number(a.ok) - Number(b.ok));
  const running = job.status === "QUEUED" || job.status === "RUNNING";
  return (
    <Panel
      title={job.title}
      note={running ? t("Running in the background: you can leave this page") : undefined}
      flush
      actions={
        <>
          <JobStatusTag job={job} />
          {running ? (
            <button className="btn small" onClick={() => cancel.mutate()} disabled={job.cancelRequested}>
              <Square size={12} aria-hidden /> {t("Stop")}
            </button>
          ) : (
            <button className="icon-btn" aria-label={t("Close the results")} onClick={onClose}>
              <X size={14} />
            </button>
          )}
        </>
      }
    >
      <div style={{ padding: "8px 12px" }}>
        <JobProgress job={job} />
      </div>
      {job.error && <div className="banner error">{job.error}</div>}
      {results.length > 0 && (
        <table className="grid" aria-label={t("Results")}>
          <thead>
            <tr>
              <th>{t("Object")}</th>
              <th>{t("Outcome")}</th>
            </tr>
          </thead>
          <tbody>
            {results.map((r) => (
              <tr key={r.objectId ?? r.number ?? r.message}>
                <td className="nowrap">
                  {r.number ? <Link to={`/objects/${r.objectId}`}>{r.number}</Link> : <span className="muted">{t("Deleted")}</span>}
                </td>
                <td>
                  <span className={r.ok ? "tag allow" : "tag deny"}>{r.ok ? t("done") : t("not changed")}</span> {r.message}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Panel>
  );
}
