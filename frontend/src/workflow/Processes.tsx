import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronDown, ChevronRight, CircleStop, Play } from "lucide-react";
import { useState } from "react";
import { api, unwrap, type Schemas } from "../api/client";
import { useMe, type VersionedObject } from "../api/queries";
import { Dialog, ErrorBanner, Loading, Panel, PrincipalName, formatDate } from "../components/common";
import { t } from "../i18n/i18n";

type Instance = Schemas["ProcessInstance"];
type Task = Schemas["WorkItem"];

/** Processes (roadmap 5.1): what runs on an object, step by step. */

const STATUS_CLASS: Record<string, string> = { RUNNING: "tag accent", COMPLETED: "tag allow", CANCELLED: "tag warn", FAILED: "tag deny" };

export function statusLabel(s: string): string {
  switch (s) {
    case "RUNNING":
      return t("running");
    case "COMPLETED":
      return t("completed");
    case "CANCELLED":
      return t("cancelled");
    case "FAILED":
      return t("failed");
  }
  return s.toLowerCase();
}

/** APPROVE → Approve. */
export const choiceLabel = (c: string) => c.charAt(0) + c.slice(1).toLowerCase().replace(/_/g, " ");

export const useObjectProcesses = (id: string) =>
  useQuery({
    queryKey: ["processes", "object", id],
    queryFn: () => unwrap(api.GET("/api/v1/objects/{id}/processes", { params: { path: { id } } })),
    refetchInterval: (q) => (q.state.data?.some((p) => p.status === "RUNNING") ? 3000 : false),
  });

export function ProcessesTab({ obj }: { obj: VersionedObject }) {
  const { data, error, isLoading } = useObjectProcesses(obj.id);
  const [starting, setStarting] = useState(false);
  const [open, setOpen] = useState<string | null>(null);
  const shown = open ?? data?.find((p) => p.status === "RUNNING")?.id ?? null;
  return (
    <Panel
      title={t("Processes")}
      flush
      actions={
        <button className="btn small primary" onClick={() => setStarting(true)}>
          <Play size={13} aria-hidden /> {t("Start a process…")}
        </button>
      }
    >
      <ErrorBanner error={error} />
      {isLoading ? (
        <Loading />
      ) : !data?.length ? (
        <div className="empty">{t("No process has run on it yet.")}</div>
      ) : (
        <table className="grid" aria-label={t("Processes")}>
          <thead>
            <tr>
              <th />
              <th>{t("Process")}</th>
              <th>{t("Status")}</th>
              <th>{t("Now")}</th>
              <th>{t("Started")}</th>
              <th>{t("Ended")}</th>
            </tr>
          </thead>
          <tbody>
            {data.map((p) => (
              <ProcessRow key={p.id} p={p} open={shown === p.id} onToggle={() => setOpen(shown === p.id ? "" : p.id)} />
            ))}
          </tbody>
        </table>
      )}
      {starting && <StartDialog obj={obj} onClose={() => setStarting(false)} onStarted={(id) => (setStarting(false), setOpen(id))} />}
    </Panel>
  );
}

function ProcessRow({ p, open, onToggle }: { p: Instance; open: boolean; onToggle: () => void }) {
  return (
    <>
      <tr className={open ? "selected" : undefined}>
        <td className="check-cell">
          <button
            className="icon-btn"
            aria-label={open ? t("Hide the steps") : t("Show the steps")}
            aria-expanded={open}
            onClick={onToggle}
          >
            {open ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
          </button>
        </td>
        <td>
          <strong>{p.name}</strong> <span className="muted small">v{p.templateVersion}</span>
          {p.comment && <div className="muted small">{p.comment}</div>}
        </td>
        <td>
          <span className={STATUS_CLASS[p.status] ?? "tag"}>{statusLabel(p.status)}</span>
          {p.outcome && <span className="muted"> · {choiceLabel(p.outcome)}</span>}
        </td>
        <td>{p.current.join(", ") || "—"}</td>
        <td className="nowrap">
          {formatDate(p.startedAt)} · <PrincipalName id={p.startedBy} />
        </td>
        <td className="nowrap">{p.endedAt ? formatDate(p.endedAt) : "—"}</td>
      </tr>
      {open && (
        <tr className="detail-row">
          <td colSpan={6}>
            <ProcessDetail p={p} />
          </td>
        </tr>
      )}
    </>
  );
}

/** The steps so far (history) and the process's tasks; the user answers theirs here. */
export function ProcessDetail({ p }: { p: Instance }) {
  const qc = useQueryClient();
  const running = p.status === "RUNNING";
  const history = useQuery({
    queryKey: ["processes", p.id, "history"],
    queryFn: () => unwrap(api.GET("/api/v1/processes/{id}/history", { params: { path: { id: p.id } } })),
    refetchInterval: running ? 2000 : false,
  });
  const tasks = useQuery({
    queryKey: ["processes", p.id, "tasks"],
    queryFn: () => unwrap(api.GET("/api/v1/processes/{id}/tasks", { params: { path: { id: p.id } } })),
    refetchInterval: running ? 2000 : false,
  });
  const me = useMe();
  const cancel = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/processes/{id}/cancel", { params: { path: { id: p.id } }, body: { reason: null } })),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["processes"] }),
  });
  return (
    <div className="process-detail">
      <div>
        <div className="section-label">{t("Steps")}</div>
        <ol className="timeline" aria-label={t("Process history")}>
          {history.data?.map((e, i) => (
            <li key={i} className={`ev ev-${e.kind.toLowerCase()}`}>
              <span className="muted small nowrap">{formatDate(e.at)}</span>
              <span>
                {e.message}
                {e.actor && e.actor !== "process" && (
                  <span className="muted">
                    {" "}
                    · <PrincipalName id={e.actor} />
                  </span>
                )}
              </span>
            </li>
          ))}
        </ol>
      </div>
      <div>
        <div className="section-label">{t("Tasks")}</div>
        <table className="grid compact" aria-label={t("Process tasks")}>
          <tbody>
            {tasks.data?.map((w) => (
              <TaskLine key={w.id} w={w} mine={w.assignee === me.data?.user.id && (!w.claimedBy || w.claimedBy === me.data?.user.id)} />
            ))}
          </tbody>
        </table>
        {running && (p.startedBy === me.data?.user.id || me.data?.siteAdmin) && (
          <button className="btn small ghost danger" style={{ marginTop: 8 }} onClick={() => cancel.mutate()} disabled={cancel.isPending}>
            <CircleStop size={13} aria-hidden /> {t("Cancel the process")}
          </button>
        )}
        <ErrorBanner error={cancel.error} />
      </div>
    </div>
  );
}

function TaskLine({ w, mine }: { w: Task; mine: boolean }) {
  const qc = useQueryClient();
  const [comment, setComment] = useState("");
  const answer = useMutation({
    mutationFn: (outcome: string) =>
      unwrap(
        api.POST("/api/v1/tasks/{id}/complete", { params: { path: { id: w.id } }, body: { outcome, comment: comment.trim() || null } }),
      ),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["processes"] }),
  });
  return (
    <tr>
      <td>
        <strong>{w.title}</strong>
        <div className="muted small">
          <PrincipalName id={w.assignee} />
          {w.dueAt && w.status === "OPEN" && <> · {t("due {date}", { date: formatDate(w.dueAt) })}</>}
        </div>
      </td>
      <td>
        {w.status === "OPEN" && mine ? (
          <div className="task-answer">
            <input
              aria-label={t("Comment")}
              placeholder={t("Comment (optional)")}
              value={comment}
              onChange={(e) => setComment(e.target.value)}
            />
            {w.choices.map((c, i) => (
              <button
                key={c}
                className={i === 0 ? "btn small primary" : "btn small"}
                disabled={answer.isPending}
                onClick={() => answer.mutate(c)}
              >
                {choiceLabel(c)}
              </button>
            ))}
            <ErrorBanner error={answer.error} />
          </div>
        ) : w.status === "DONE" ? (
          <span>
            <span className="tag">{choiceLabel(w.outcome ?? "")}</span> {w.comment && <span className="muted">“{w.comment}”</span>}
          </span>
        ) : w.status === "CANCELLED" ? (
          <span className="muted">{t("not needed")}</span>
        ) : (
          <span className="muted">{t("waiting")}</span>
        )}
      </td>
    </tr>
  );
}

function StartDialog({ obj, onClose, onStarted }: { obj: VersionedObject; onClose: () => void; onStarted: (id: string) => void }) {
  const qc = useQueryClient();
  const { data: templates } = useQuery({ queryKey: ["processTemplates"], queryFn: () => unwrap(api.GET("/api/v1/process-templates")) });
  const active = (templates ?? []).filter((x) => x.active);
  const [templateId, setTemplateId] = useState("");
  const [comment, setComment] = useState("");
  const chosen = templateId || active[0]?.id || "";
  const start = useMutation({
    mutationFn: () =>
      unwrap(api.POST("/api/v1/processes", { body: { templateId: chosen, subjectId: obj.id, comment: comment.trim() || null } })),
    onSuccess: (p) => {
      void qc.invalidateQueries({ queryKey: ["processes"] });
      void qc.invalidateQueries({ queryKey: ["object", obj.id] });
      onStarted(p.id);
    },
  });
  const template = active.find((x) => x.id === chosen);
  return (
    <Dialog
      open
      title={t("Start a process on {number}", { number: obj.number })}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            {t("Cancel")}
          </button>
          <button className="btn primary" disabled={!chosen || start.isPending} onClick={() => start.mutate()}>
            {t("Start")}
          </button>
        </>
      }
    >
      <div style={{ display: "grid", gap: 12 }}>
        <div className="field">
          <label htmlFor="process-template">{t("Process")}</label>
          <select id="process-template" value={chosen} onChange={(e) => setTemplateId(e.target.value)}>
            {active.map((x) => (
              <option key={x.id} value={x.id}>
                {x.name}
              </option>
            ))}
          </select>
          {template?.description && <span className="hint">{template.description}</span>}
        </div>
        <div className="field">
          <label htmlFor="process-comment">{t("Note for the people involved")}</label>
          <input id="process-comment" value={comment} onChange={(e) => setComment(e.target.value)} />
        </div>
        <ErrorBanner error={start.error} />
      </div>
    </Dialog>
  );
}
