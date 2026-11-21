import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Hand, Inbox as InboxIcon, Undo2 } from "lucide-react";
import { useState } from "react";
import { Link, NavLink, useSearchParams } from "react-router";
import { api, unwrap, type Schemas } from "../api/client";
import { useMe } from "../api/queries";
import { ErrorBanner, Loading, Panel, PrincipalName, formatDate } from "../components/common";
import { PageHeader } from "../components/Layout";
import { t, tn } from "../i18n/i18n";
import { ProcessDetail, choiceLabel } from "./Processes";

type Task = Schemas["WorkItem"];

/** Tasks (roadmap 5.3): what the processes ask of me, with what's needed to answer. */

export const useTaskCounts = () =>
  useQuery({
    queryKey: ["tasks", "counts"],
    queryFn: () => unwrap(api.GET("/api/v1/tasks/counts")),
    refetchInterval: 30_000,
  });

/** The top bar's link to the inbox, with how many tasks are open (and overdue). */
export function InboxLink() {
  const { data } = useTaskCounts();
  const open = data?.open ?? 0;
  return (
    <NavLink to="/inbox" className="topbar-link" aria-label={tn("Tasks: {n} open", "Tasks: {n} open", open)}>
      <InboxIcon size={14} aria-hidden /> {t("Tasks")}
      {open > 0 && <span className={data?.overdue ? "count-badge overdue" : "count-badge"}>{open}</span>}
    </NavLink>
  );
}

type Filter = "open" | "overdue" | "done";

const overdue = (w: Task) => w.status === "OPEN" && !!w.dueAt && new Date(w.dueAt).getTime() < Date.now();

export function InboxPage() {
  const [params, setParams] = useSearchParams();
  const filter = (params.get("show") as Filter | null) ?? "open";
  const { data, error, isLoading } = useQuery({
    queryKey: ["tasks", "list", filter],
    queryFn: () =>
      unwrap(api.GET("/api/v1/tasks", { params: { query: filter === "done" ? { status: "DONE" as const, limit: 100 } : { limit: 200 } } })),
    refetchInterval: 10_000,
  });
  const [text, setText] = useState("");
  const rows = (data ?? [])
    .filter((w) => filter !== "overdue" || overdue(w))
    .filter((w) => {
      const q = text.trim().toLowerCase();
      return !q || [w.title, w.processName, w.subjectNumber, w.subjectName].some((x) => x?.toLowerCase().includes(q));
    });
  const chosenId = params.get("task") ?? rows[0]?.id ?? null;
  const set = (k: string, v: string | null) => {
    const next = new URLSearchParams(params);
    if (v) next.set(k, v);
    else next.delete(k);
    setParams(next);
  };
  return (
    <>
      <PageHeader
        icon={<InboxIcon size={18} className="icon-muted" aria-hidden />}
        title={<span className="nm">{t("Tasks")}</span>}
        meta={<span className="sub">{t("What processes ask of you: answer here, or open the object first.")}</span>}
      />
      <div className="content inbox">
        <Panel
          title={t("My tasks")}
          flush
          actions={
            <>
              <span className="segmented" role="tablist" aria-label={t("Show")}>
                {(
                  [
                    ["open", t("Open")],
                    ["overdue", t("Overdue")],
                    ["done", t("Done")],
                  ] as const
                ).map(([k, l]) => (
                  <button
                    key={k}
                    role="tab"
                    aria-selected={filter === k}
                    className={filter === k ? "on" : undefined}
                    onClick={() => setParams({ show: k })}
                  >
                    {l}
                  </button>
                ))}
              </span>
              <input
                className="filter"
                aria-label={t("Filter tasks")}
                placeholder={t("Filter")}
                value={text}
                onChange={(e) => setText(e.target.value)}
              />
            </>
          }
        >
          <ErrorBanner error={error} />
          {isLoading ? (
            <Loading />
          ) : rows.length === 0 ? (
            <div className="empty">{filter === "done" ? t("You haven't answered any task yet.") : t("Nothing to do. Well done!")}</div>
          ) : (
            <table className="grid" aria-label={t("My tasks")}>
              <thead>
                <tr>
                  <th>{t("Task")}</th>
                  <th>{t("Object")}</th>
                  <th>{t("Process")}</th>
                  <th>{filter === "done" ? t("Answered") : t("Due")}</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((w) => (
                  <tr
                    key={w.id}
                    className={w.id === chosenId ? "selected clickable" : "clickable"}
                    onClick={() => set("task", w.id)}
                    aria-selected={w.id === chosenId}
                  >
                    <td>
                      <strong>{w.title}</strong>
                      {w.kind === "VOTE" && <span className="tag">{t("vote")}</span>}
                      {w.claimedBy && <span className="tag accent">{t("taken on")}</span>}
                    </td>
                    <td>
                      {w.subjectNumber ? (
                        <>
                          <span className="mono">{w.subjectNumber}</span> {w.subjectName}
                        </>
                      ) : (
                        "—"
                      )}
                    </td>
                    <td>{w.processName}</td>
                    <td className={overdue(w) ? "nowrap overdue-text" : "nowrap"}>
                      {filter === "done"
                        ? `${choiceLabel(w.outcome ?? "")} · ${formatDate(w.completedAt)}`
                        : w.dueAt
                          ? formatDate(w.dueAt)
                          : "—"}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Panel>
        {chosenId && <TaskPanel key={chosenId} id={chosenId} />}
      </div>
    </>
  );
}

/** One task: its instructions, what it's about, the process so far, and the answer. */
function TaskPanel({ id }: { id: string }) {
  const qc = useQueryClient();
  const { data: me } = useMe();
  const { data: w, error } = useQuery({
    queryKey: ["tasks", id],
    queryFn: () => unwrap(api.GET("/api/v1/tasks/{id}", { params: { path: { id } } })),
  });
  const process = useQuery({
    queryKey: ["processes", w?.instanceId],
    queryFn: () => unwrap(api.GET("/api/v1/processes/{id}", { params: { path: { id: w!.instanceId } } })),
    enabled: !!w,
  });
  const [comment, setComment] = useState("");
  const refresh = () => {
    void qc.invalidateQueries({ queryKey: ["tasks"] });
    void qc.invalidateQueries({ queryKey: ["processes"] });
  };
  const answer = useMutation({
    mutationFn: (outcome: string) =>
      unwrap(api.POST("/api/v1/tasks/{id}/complete", { params: { path: { id } }, body: { outcome, comment: comment.trim() || null } })),
    onSuccess: refresh,
  });
  const claim = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/tasks/{id}/claim", { params: { path: { id } } })),
    onSuccess: refresh,
  });
  const release = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/tasks/{id}/release", { params: { path: { id } } })),
    onSuccess: refresh,
  });
  if (error) return <ErrorBanner error={error} />;
  if (!w) return <Loading />;
  const open = w.status === "OPEN";
  const claimedByOther = !!w.claimedBy && w.claimedBy !== me?.user.id;
  return (
    <Panel title={w.title} note={w.processName}>
      <div className="task-panel">
        {w.subjectId && (
          <div>
            <span className="label">{t("About")}</span>{" "}
            <Link to={`/objects/${w.subjectId}`}>
              <span className="mono">{w.subjectNumber}</span> {w.subjectName}
            </Link>{" "}
            · <Link to={`/objects/${w.subjectId}?tab=processes`}>{t("its processes")}</Link>
          </div>
        )}
        {w.instructions && <div className="instructions">{w.instructions}</div>}
        {process.data?.comment && (
          <div className="muted">
            “{process.data.comment}” — <PrincipalName id={process.data.startedBy} />
          </div>
        )}
        <div className="muted small">
          {t("Given {date}", { date: formatDate(w.createdAt) })}
          {w.dueAt && <> · {t("due {date}", { date: formatDate(w.dueAt) })}</>}
          {w.sharedWith > 1 && <> · {tn("given to {n} person", "given to {n} people", w.sharedWith)}</>}
        </div>
        {open ? (
          <>
            {claimedByOther && (
              <div className="banner info">
                <PrincipalName id={w.claimedBy} /> {t("has taken this task on.")}
              </div>
            )}
            {w.kind === "TASK" && w.sharedWith > 1 && !w.claimedBy && (
              <div className="row">
                <button className="btn small" onClick={() => claim.mutate()} disabled={claim.isPending}>
                  <Hand size={13} aria-hidden /> {t("Take it on")}
                </button>
                <span className="muted small">{t("so the others don't do it twice")}</span>
              </div>
            )}
            {w.claimedBy === me?.user.id && (
              <div className="row">
                <button className="btn small" onClick={() => release.mutate()} disabled={release.isPending}>
                  <Undo2 size={13} aria-hidden /> {t("Give it back")}
                </button>
              </div>
            )}
            <div className="field">
              <label htmlFor="task-comment">{t("Comment")}</label>
              <textarea id="task-comment" rows={3} value={comment} onChange={(e) => setComment(e.target.value)} disabled={claimedByOther} />
            </div>
            <div className="row" style={{ gap: 8 }}>
              {w.choices.map((c, i) => (
                <button
                  key={c}
                  className={i === 0 ? "btn primary" : "btn"}
                  disabled={answer.isPending || claimedByOther}
                  onClick={() => answer.mutate(c)}
                >
                  {choiceLabel(c)}
                </button>
              ))}
            </div>
            <ErrorBanner error={answer.error ?? claim.error ?? release.error} />
          </>
        ) : (
          <div className="banner info">
            {w.status === "DONE"
              ? t("Answered {answer} on {date}", { answer: choiceLabel(w.outcome ?? ""), date: formatDate(w.completedAt) })
              : t("No longer needed")}
            {w.comment && <> — “{w.comment}”</>}
          </div>
        )}
        {process.data && <ProcessDetail p={process.data} />}
      </div>
    </Panel>
  );
}
