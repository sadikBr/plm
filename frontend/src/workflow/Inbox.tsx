import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Hand, Inbox as InboxIcon, Undo2, UserRoundPlus } from "lucide-react";
import { useState } from "react";
import { Link, NavLink, useSearchParams } from "react-router";
import { api, unwrap, type Schemas } from "../api/client";
import { useMe } from "../api/queries";
import { ErrorBanner, Loading, Panel, PrincipalName, formatDate, When } from "../components/common";
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
        meta={
          <span className="sub">
            {t("What processes ask of you: answer here, or open the object first.")} <Link to="/promotions">{t("Promotion requests")}</Link>
          </span>
        }
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
            <ul className="task-cards" aria-label={t("My tasks")}>
              {rows.map((w) => (
                <li key={w.id}>
                  <button
                    className={w.id === chosenId ? "task-card selected" : "task-card"}
                    aria-current={w.id === chosenId ? "true" : undefined}
                    onClick={() => set("task", w.id)}
                  >
                    <span className="task-card-head">
                      <strong>{w.title}</strong>
                      {w.kind === "VOTE" && <span className="tag">{t("vote")}</span>}
                      {w.claimedBy && <span className="tag accent">{t("taken on")}</span>}
                      <span className="grow" />
                      <span className={overdue(w) ? "task-due overdue-text" : "task-due"}>
                        {filter === "done" ? (
                          <>
                            {choiceLabel(w.outcome ?? "")} · <When iso={w.completedAt} />
                          </>
                        ) : w.dueAt ? (
                          <When iso={w.dueAt} />
                        ) : null}
                      </span>
                    </span>
                    <span className="task-card-subject">
                      {w.subjectNumber ? (
                        <>
                          <span className="mono">{w.subjectNumber}</span> {w.subjectName}
                        </>
                      ) : (
                        (w.about ?? "—")
                      )}
                    </span>
                    <span className="task-card-process">{w.processName}</span>
                  </button>
                </li>
              ))}
            </ul>
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
  const [password, setPassword] = useState("");
  const refresh = () => {
    void qc.invalidateQueries({ queryKey: ["tasks"] });
    void qc.invalidateQueries({ queryKey: ["processes"] });
  };
  const answer = useMutation({
    mutationFn: (outcome: string) =>
      unwrap(
        api.POST("/api/v1/tasks/{id}/complete", {
          params: { path: { id } },
          body: { outcome, comment: comment.trim() || null, password: w?.signature ? password : null },
        }),
      ),
    onSuccess: refresh,
    onError: () => setPassword(""),
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
        {w.subjectId &&
          (w.subjectNumber ? (
            <div>
              <span className="label">{t("About")}</span>{" "}
              <Link to={`/objects/${w.subjectId}`}>
                <span className="mono">{w.subjectNumber}</span> {w.subjectName}
              </Link>{" "}
              · <Link to={`/objects/${w.subjectId}?tab=processes`}>{t("its processes")}</Link>
            </div>
          ) : (
            <div className="muted">{t("About an object you can't open: ask for access, or pass the task on.")}</div>
          ))}
        {!w.subjectId && w.about && (
          <div>
            <span className="label">{t("About")}</span> {w.link ? <Link to={w.link}>{w.about}</Link> : w.about}
          </div>
        )}
        {w.instructions && <div className="instructions">{w.instructions}</div>}
        {process.data?.comment && (
          <div className="muted">
            “{process.data.comment}” — <PrincipalName id={process.data.startedBy} />
          </div>
        )}
        {w.onBehalfOf && (
          <div className="muted">
            {t("For")} <PrincipalName id={w.onBehalfOf} />
          </div>
        )}
        {w.kind === "VOTE" && (
          <div className="vote-tally" aria-label={t("Votes so far")}>
            <span className="label">{t("Votes so far")}</span>
            {w.choices.map((c) => (
              <span key={c} className="tag">
                {choiceLabel(c)}: {w.tally[c] ?? 0}
              </span>
            ))}
            <span className="muted small">
              {w.rule === "ANY" ? t("One approval is enough") : w.rule === "MAJORITY" ? t("By majority") : t("All must approve")}
            </span>
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
            {w.signature && (
              <div className="signing">
                <div className="field">
                  <label htmlFor="task-password">{t("Your password, to sign")}</label>
                  <input
                    id="task-password"
                    type="password"
                    autoComplete="current-password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    disabled={claimedByOther}
                  />
                </div>
                <p className="small">
                  {t(
                    "Answering signs it electronically: your name, the time and what you chose are recorded with this version, and can't be changed. It is the equivalent of your handwritten signature.",
                  )}
                </p>
              </div>
            )}
            <div className="task-answer">
              {w.choices.map((c, i) => (
                <button
                  key={c}
                  className={i === 0 ? "btn primary" : "btn"}
                  disabled={answer.isPending || claimedByOther || (w.signature && !password)}
                  onClick={() => answer.mutate(c)}
                >
                  {choiceLabel(c)}
                </button>
              ))}
              {w.signature && !password && !claimedByOther && <span className="muted small">{t("Enter your password to answer.")}</span>}
            </div>
            <ErrorBanner error={answer.error ?? claim.error ?? release.error} />
            <Reassign id={w.id} />
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

/** Pass a task to someone else (roadmap 5.4). */
function Reassign({ id }: { id: string }) {
  const qc = useQueryClient();
  const [open, setOpen] = useState(false);
  const [to, setTo] = useState("");
  const [note, setNote] = useState("");
  const pass = useMutation({
    mutationFn: () =>
      unwrap(api.POST("/api/v1/tasks/{id}/reassign", { params: { path: { id } }, body: { to: to.trim(), comment: note.trim() || null } })),
    onSuccess: () => {
      setOpen(false);
      void qc.invalidateQueries({ queryKey: ["tasks"] });
      void qc.invalidateQueries({ queryKey: ["processes"] });
    },
  });
  if (!open)
    return (
      <div className="row">
        <button className="link-btn small" onClick={() => setOpen(true)}>
          <UserRoundPlus size={13} aria-hidden /> {t("Pass it to someone else…")}
        </button>
      </div>
    );
  return (
    <form
      className="reassign"
      onSubmit={(e) => {
        e.preventDefault();
        pass.mutate();
      }}
    >
      <input
        aria-label={t("To (username)")}
        placeholder={t("To (username)")}
        value={to}
        onChange={(e) => setTo(e.target.value)}
        autoFocus
      />
      <input aria-label={t("Why")} placeholder={t("Why (optional)")} value={note} onChange={(e) => setNote(e.target.value)} />
      <button className="btn small" type="submit" disabled={!to.trim() || pass.isPending}>
        {t("Pass it on")}
      </button>
      <button className="btn small ghost" type="button" onClick={() => setOpen(false)}>
        {t("Cancel")}
      </button>
      <ErrorBanner error={pass.error} />
    </form>
  );
}
