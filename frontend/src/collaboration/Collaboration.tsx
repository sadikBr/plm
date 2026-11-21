import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckCircle2, CircleDot, MessageSquare, Pencil, Reply, Share2, Trash2, UserX } from "lucide-react";
import { Fragment, useState, type FormEvent, type ReactNode } from "react";
import { NavLink } from "react-router";
import { api, unwrap, type Schemas } from "../api/client";
import { useMe, type VersionedObject } from "../api/queries";
import { Dialog, ErrorBanner, Loading, PrincipalName, TypeIcon, formatDate } from "../components/common";
import { t, tn } from "../i18n/i18n";

// Discussions and ad hoc sharing (roadmap 3.10).

type Comment = Schemas["Comment"];
type Share = Schemas["Share"];

export const useComments = (objectId: string) =>
  useQuery({
    queryKey: ["comments", objectId],
    queryFn: () => unwrap(api.GET("/api/v1/objects/{id}/comments", { params: { path: { id: objectId } } })),
  });

export const useSharedWithMe = () =>
  useQuery({ queryKey: ["me", "sharedWithMe"], queryFn: () => unwrap(api.GET("/api/v1/me/shared-with-me")) });

/** Turns `@name` into a highlighted mention; keeps line breaks. */
function CommentBody({ text }: { text: string }) {
  const parts = text.split(/((?<![\w.])@[A-Za-z0-9._-]{2,64})/g);
  return (
    <div className="comment-body">
      {parts.map((p, i) =>
        p.startsWith("@") ? (
          <span key={i} className="mention">
            {p}
          </span>
        ) : (
          <Fragment key={i}>{p}</Fragment>
        ),
      )}
    </div>
  );
}

function Composer({
  placeholder,
  initial = "",
  submitLabel,
  busy,
  onSubmit,
  onCancel,
}: {
  placeholder: string;
  initial?: string;
  submitLabel: string;
  busy: boolean;
  onSubmit: (body: string, reset: () => void) => void;
  onCancel?: () => void;
}) {
  const [body, setBody] = useState(initial);
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (body.trim()) onSubmit(body, () => setBody(""));
  };
  return (
    <form className="composer" onSubmit={submit}>
      <textarea
        aria-label={placeholder}
        placeholder={placeholder}
        value={body}
        rows={Math.min(8, Math.max(2, body.split("\n").length))}
        onChange={(e) => setBody(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) submit(e);
        }}
      />
      <div className="row" style={{ gap: 6, justifyContent: "flex-end" }}>
        <span className="muted small grow">{t("Type @username to mention someone · Ctrl+Enter to send")}</span>
        {onCancel && (
          <button type="button" className="btn small ghost" onClick={onCancel}>
            {t("Cancel")}
          </button>
        )}
        <button className="btn small primary" type="submit" disabled={busy || !body.trim()}>
          {submitLabel}
        </button>
      </div>
    </form>
  );
}

function CommentView({
  c,
  me,
  admin,
  actions,
  onChanged,
}: {
  c: Comment;
  me?: string;
  admin: boolean;
  actions?: ReactNode;
  onChanged: () => void;
}) {
  const [editing, setEditing] = useState(false);
  const edit = useMutation({
    mutationFn: (body: string) =>
      unwrap(api.PUT("/api/v1/comments/{commentId}", { params: { path: { commentId: c.id } }, body: { body } })),
    onSuccess: () => {
      setEditing(false);
      onChanged();
    },
  });
  const remove = useMutation({
    mutationFn: () => unwrap(api.DELETE("/api/v1/comments/{commentId}", { params: { path: { commentId: c.id } } })),
    onSuccess: onChanged,
  });
  const mine = c.author === me;
  return (
    <div className="comment" data-testid="comment">
      <div className="comment-head">
        <strong>
          <PrincipalName id={c.author} />
        </strong>
        <span className="muted small">
          {formatDate(c.createdAt)}
          {c.editedAt && ` · ${t("edited")}`}
        </span>
        <span className="grow" />
        {actions}
        {!c.deleted && mine && !editing && (
          <button className="icon-btn" aria-label={t("Edit the comment")} title={t("Edit")} onClick={() => setEditing(true)}>
            <Pencil size={13} />
          </button>
        )}
        {!c.deleted && (mine || admin) && (
          <button
            className="icon-btn"
            aria-label={t("Delete the comment")}
            title={t("Delete")}
            onClick={() => window.confirm(t("Delete this comment?")) && remove.mutate()}
          >
            <Trash2 size={13} />
          </button>
        )}
      </div>
      <ErrorBanner error={edit.error ?? remove.error} />
      {c.deleted ? (
        <p className="muted small">{t("This comment was deleted.")}</p>
      ) : editing ? (
        <Composer
          placeholder={t("Edit the comment")}
          initial={c.body}
          submitLabel={t("Save")}
          busy={edit.isPending}
          onSubmit={(b) => edit.mutate(b)}
          onCancel={() => setEditing(false)}
        />
      ) : (
        <CommentBody text={c.body} />
      )}
    </div>
  );
}

/** The object's Discussion tab: topics with their replies, newest topic last. */
export function DiscussionTab({ obj }: { obj: VersionedObject }) {
  const qc = useQueryClient();
  const { data: me } = useMe();
  const { data, error, isLoading } = useComments(obj.id);
  const [replyTo, setReplyTo] = useState<string | null>(null);
  const [showResolved, setShowResolved] = useState(false);
  const refresh = () => void qc.invalidateQueries({ queryKey: ["comments", obj.id] });
  const post = useMutation({
    mutationFn: (c: { body: string; parentId?: string }) =>
      unwrap(api.POST("/api/v1/objects/{id}/comments", { params: { path: { id: obj.id } }, body: c })),
    onSuccess: () => {
      setReplyTo(null);
      refresh();
    },
  });
  const resolve = useMutation({
    mutationFn: (c: { id: string; resolved: boolean }) =>
      unwrap(api.PUT("/api/v1/comments/{commentId}/resolved", { params: { path: { commentId: c.id } }, body: { resolved: c.resolved } })),
    onSuccess: refresh,
  });
  if (isLoading) return <Loading />;
  const topics = (data ?? []).filter((c) => !c.parentId);
  const resolvedCount = topics.filter((topic) => topic.resolved).length;
  const shown = topics.filter((topic) => showResolved || !topic.resolved);
  return (
    <div className="discussion">
      <ErrorBanner error={error ?? post.error ?? resolve.error} />
      <div className="row" style={{ gap: 8 }}>
        <MessageSquare size={15} className="icon-muted" aria-hidden />
        <strong>{tn("{n} topic", "{n} topics", topics.length)}</strong>
        {resolvedCount > 0 && (
          <label className="row small muted" style={{ gap: 4 }}>
            <input type="checkbox" checked={showResolved} onChange={(e) => setShowResolved(e.target.checked)} />
            {t("Show {count} resolved", { count: resolvedCount })}
          </label>
        )}
      </div>
      {shown.length === 0 && <p className="empty">{t("No discussion yet. Ask a question or leave a note for the team.")}</p>}
      {shown.map((topic) => (
        <section key={topic.id} className={topic.resolved ? "topic resolved" : "topic"} aria-label={t("Topic")}>
          <CommentView
            c={topic}
            me={me?.user.id}
            admin={!!me?.siteAdmin}
            onChanged={refresh}
            actions={
              <button
                className={topic.resolved ? "track-btn on" : "track-btn"}
                onClick={() => resolve.mutate({ id: topic.id, resolved: !topic.resolved })}
                title={topic.resolved ? t("Reopen the topic") : t("Mark the topic as resolved")}
              >
                {topic.resolved ? <CheckCircle2 size={13} aria-hidden /> : <CircleDot size={13} aria-hidden />}
                {topic.resolved ? t("Resolved") : t("Resolve")}
              </button>
            }
          />
          <div className="replies">
            {(data ?? [])
              .filter((r) => r.parentId === topic.id)
              .map((r) => (
                <CommentView key={r.id} c={r} me={me?.user.id} admin={!!me?.siteAdmin} onChanged={refresh} />
              ))}
            {replyTo === topic.id ? (
              <Composer
                placeholder={t("Write a reply")}
                submitLabel={t("Reply")}
                busy={post.isPending}
                onSubmit={(body) => post.mutate({ body, parentId: topic.id })}
                onCancel={() => setReplyTo(null)}
              />
            ) : (
              <button className="link-btn reply-btn" onClick={() => setReplyTo(topic.id)}>
                <Reply size={13} aria-hidden /> {t("Reply")}
              </button>
            )}
          </div>
        </section>
      ))}
      <div className="topic new-topic">
        <Composer
          placeholder={t("Start a topic")}
          submitLabel={t("Post")}
          busy={post.isPending}
          onSubmit={(body, reset) => post.mutate({ body }, { onSuccess: reset })}
        />
      </div>
    </div>
  );
}

/** "Share" in the object header: who else may see (or edit) this one object. */
export function ShareButton({ obj }: { obj: VersionedObject }) {
  const [open, setOpen] = useState(false);
  const { data } = useQuery({
    queryKey: ["shares", obj.id],
    queryFn: () => unwrap(api.GET("/api/v1/objects/{id}/shares", { params: { path: { id: obj.id } } })),
  });
  return (
    <>
      <button
        className={data?.length ? "track-btn on" : "track-btn"}
        onClick={() => setOpen(true)}
        title={t("Share this object with someone")}
      >
        <Share2 size={14} aria-hidden />
        {data?.length ? t("Shared with {count}", { count: data.length }) : t("Share")}
      </button>
      {open && <ShareDialog obj={obj} shares={data ?? []} onClose={() => setOpen(false)} />}
    </>
  );
}

function ShareDialog({ obj, shares, onClose }: { obj: VersionedObject; shares: Share[]; onClose: () => void }) {
  const qc = useQueryClient();
  const [user, setUser] = useState("");
  const [modify, setModify] = useState(false);
  const [until, setUntil] = useState("");
  const [note, setNote] = useState("");
  const refresh = () => void qc.invalidateQueries({ queryKey: ["shares", obj.id] });
  const add = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/api/v1/objects/{id}/shares", {
          params: { path: { id: obj.id } },
          body: {
            principalId: user.trim(),
            modify,
            note: note.trim() || undefined,
            expiresAt: until ? new Date(`${until}T23:59:59`).toISOString() : undefined,
          },
        }),
      ),
    onSuccess: () => {
      setUser("");
      setNote("");
      refresh();
    },
  });
  const remove = useMutation({
    mutationFn: (principalId: string) =>
      unwrap(api.DELETE("/api/v1/objects/{id}/shares/{principalId}", { params: { path: { id: obj.id, principalId } } })),
    onSuccess: refresh,
  });
  return (
    <Dialog
      open
      title={t("Share {number}", { number: obj.number })}
      onClose={onClose}
      footer={
        <button className="btn" onClick={onClose}>
          {t("Done")}
        </button>
      }
    >
      <p className="muted small" style={{ marginTop: 0 }}>
        {t(
          "For someone who isn't on the team: they see this object (and where it lives), nothing else. Its owner and the context's managers can share it.",
        )}
      </p>
      <ErrorBanner error={add.error ?? remove.error} />
      <form
        className="share-form"
        onSubmit={(e) => {
          e.preventDefault();
          add.mutate();
        }}
      >
        <div className="field">
          <label htmlFor="share-user">{t("Username")}</label>
          <input id="share-user" value={user} onChange={(e) => setUser(e.target.value)} placeholder={t("e.g. vera")} required />
        </div>
        <div className="field">
          <label htmlFor="share-until">{t("Until (optional)")}</label>
          <input id="share-until" type="date" value={until} onChange={(e) => setUntil(e.target.value)} />
        </div>
        <div className="field" style={{ gridColumn: "1 / -1" }}>
          <label htmlFor="share-note">{t("Note (optional)")}</label>
          <input id="share-note" value={note} onChange={(e) => setNote(e.target.value)} placeholder={t("Why it's shared")} />
        </div>
        <label className="row" style={{ gap: 6 }}>
          <input type="checkbox" checked={modify} onChange={(e) => setModify(e.target.checked)} />{" "}
          {t("They may also check it out and edit it")}
        </label>
        <button className="btn primary" type="submit" disabled={add.isPending || !user.trim()}>
          <Share2 size={14} aria-hidden /> {t("Share")}
        </button>
      </form>
      <h3 className="section-title">{t("Shared with")}</h3>
      {shares.length === 0 ? (
        <p className="empty">{t("Nobody yet.")}</p>
      ) : (
        <table className="grid" aria-label={t("Shared with")}>
          <thead>
            <tr>
              <th>{t("Person")}</th>
              <th>{t("May")}</th>
              <th>{t("Until")}</th>
              <th>{t("Shared by")}</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {shares.map((s) => (
              <tr key={s.principalId}>
                <td>
                  <PrincipalName id={s.principalId} />
                  {s.note && <div className="muted small">{s.note}</div>}
                </td>
                <td>{s.permissions.includes("MODIFY") ? t("View and edit") : t("View")}</td>
                <td className="nowrap">{s.expiresAt ? formatDate(s.expiresAt) : <span className="muted">—</span>}</td>
                <td className="nowrap">
                  <PrincipalName id={s.grantedBy} />
                </td>
                <td style={{ width: 1 }}>
                  <button
                    className="icon-btn"
                    aria-label={t("Stop sharing with {name}", { name: s.principalId })}
                    title={t("Stop sharing")}
                    onClick={() => remove.mutate(s.principalId)}
                  >
                    <UserX size={14} />
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Dialog>
  );
}

/** Navigator section: what others shared with you. */
export function NavSharedWithMe({ section }: { section: (title: string, icon: ReactNode, children: ReactNode) => ReactNode }) {
  const { data } = useSharedWithMe();
  if (!data?.length) return null;
  return section(
    t("Shared with me"),
    <Share2 size={11} aria-hidden />,
    <ul className="nav-favorites" aria-label={t("Shared with me")}>
      {data.slice(0, 10).map((s) => (
        <li key={s.share.objectId}>
          <div className="nav-row">
            <NavLink to={`/objects/${s.share.objectId}`} title={`${s.number} ${s.name}`}>
              <span className="kind">
                <TypeIcon typeId={s.typeId} size={14} />
              </span>
              <span className="nav-name">
                {s.number} {s.name}
              </span>
            </NavLink>
          </div>
        </li>
      ))}
    </ul>,
  );
}
