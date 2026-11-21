import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CircleAlert, CircleCheck, Download, Info, Loader, TriangleAlert, X } from "lucide-react";
import { useEffect, useState, type FormEvent, type ReactNode } from "react";
import { useNavigate } from "react-router";
import { useInvokeAction, type ActionForm, type ActionResult, type AvailableAction } from "../api/queries";
import { api, unwrap, type Schemas } from "../api/client";
import { Dialog, ErrorBanner } from "../components/common";
import { JobProgress, JobStatusTag, duration } from "../components/JobProgress";
import { t } from "../i18n/i18n";
import { actionLabel } from "./ObjectActions";

/**
 * Runs actions carried out on the server (extension webhooks): asks the action's form, if it has
 * one, then shows what the service answered and does what it asked (refresh, navigate, download).
 */
export function useActionRunner(objectId: string): { run: (a: AvailableAction) => void; busy: boolean; ui: ReactNode; started: number } {
  const invoke = useInvokeAction(objectId);
  const navigate = useNavigate();
  const [asking, setAsking] = useState<AvailableAction | null>(null);
  const [shown, setShown] = useState<{ label: string; result: ActionResult } | null>(null);
  // Bumped when a background action starts, so its panel picks the job up at once.
  const [started, setStarted] = useState(0);

  const start = (a: AvailableAction, parameters: Record<string, unknown>) =>
    invoke.mutate(
      { actionId: a.id, parameters },
      {
        onSuccess: (result) => {
          setAsking(null);
          setShown({ label: actionLabel(a), result });
          if (result.jobId) setStarted((n) => n + 1);
          if (result.download) save(result.download);
          if (result.navigateToObject) navigate(`/objects/${result.navigateToObject}`);
          else if (result.navigateToUrl) window.open(result.navigateToUrl, "_blank", "noopener,noreferrer");
        },
      },
    );

  const run = (a: AvailableAction) => {
    invoke.reset();
    setShown(null);
    if (a.form && Object.keys(a.form.properties ?? {}).length > 0) setAsking(a);
    else start(a, {});
  };

  const ui = (
    <>
      {shown && <ResultBanner label={shown.label} result={shown.result} onClose={() => setShown(null)} />}
      {!asking && invoke.error != null && (
        <div style={{ padding: "12px 16px 0" }}>
          <ErrorBanner error={invoke.error} />
        </div>
      )}
      {asking && (
        <FormDialog
          action={asking}
          form={asking.form!}
          busy={invoke.isPending}
          error={invoke.error}
          onCancel={() => {
            setAsking(null);
            invoke.reset();
          }}
          onSubmit={(answers) => start(asking, answers)}
        />
      )}
    </>
  );
  return { run, busy: invoke.isPending, ui, started };
}

/** Hands a file the service returned to the browser. */
function save(d: NonNullable<ActionResult["download"]>) {
  const bytes = Uint8Array.from(atob(d.content), (c) => c.charCodeAt(0));
  const url = URL.createObjectURL(new Blob([bytes], { type: d.contentType }));
  const link = document.createElement("a");
  link.href = url;
  link.download = d.filename;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

const LEVEL: Record<string, { className: string; icon: ReactNode }> = {
  INFO: { className: "banner ok result", icon: <CircleCheck size={15} aria-hidden /> },
  WARNING: { className: "banner warn result", icon: <TriangleAlert size={15} aria-hidden /> },
  ERROR: { className: "banner error result", icon: <CircleAlert size={15} aria-hidden /> },
};

function ResultBanner({ label, result, onClose }: { label: string; result: ActionResult; onClose: () => void }) {
  const level = LEVEL[result.level] ?? { className: "banner info result", icon: <Info size={15} aria-hidden /> };
  return (
    <div style={{ padding: "12px 16px 0" }}>
      <div className={level.className} role={result.ok ? "status" : "alert"}>
        {level.icon}
        <span>
          <strong>{label}:</strong> {result.message}
          {result.download && <span className="muted"> · {t("downloaded {filename}", { filename: result.download.filename })}</span>}
        </span>
        <button className="icon-btn" aria-label={t("Dismiss")} onClick={onClose} style={{ marginLeft: "auto" }}>
          <X size={13} />
        </button>
      </div>
    </div>
  );
}

/** A form generated from the action's schema (text, number, yes/no, choice). */
function FormDialog({
  action,
  form,
  busy,
  error,
  onCancel,
  onSubmit,
}: {
  action: AvailableAction;
  form: ActionForm;
  busy: boolean;
  error: unknown;
  onCancel: () => void;
  onSubmit: (answers: Record<string, unknown>) => void;
}) {
  const fields = Object.entries(form.properties ?? {});
  const [answers, setAnswers] = useState<Record<string, unknown>>(
    Object.fromEntries(fields.filter(([, f]) => f.default != null).map(([k, f]) => [k, f.default])),
  );
  const required = new Set(form.required ?? []);
  const missing = fields.some(([k]) => required.has(k) && (answers[k] == null || answers[k] === ""));
  // Problems the server found, by field.
  const problems = Object.fromEntries(
    (
      ((error as { problem?: { details?: { errors?: { field: string; message: string }[] } } })?.problem?.details?.errors ?? []) as {
        field: string;
        message: string;
      }[]
    ).map((e) => [e.field, e.message]),
  );
  const submit = (e: FormEvent) => {
    e.preventDefault();
    onSubmit(answers);
  };
  const formId = "action-form";

  return (
    <Dialog
      open
      title={form.title ? `${actionLabel(action)}: ${form.title}` : actionLabel(action)}
      onClose={onCancel}
      footer={
        <>
          <button className="btn" type="button" onClick={onCancel}>
            {t("Cancel")}
          </button>
          <button className="btn primary" type="submit" form={formId} disabled={busy || missing}>
            {busy ? t("Running…") : actionLabel(action)}
          </button>
        </>
      }
    >
      <form id={formId} className="attr-form cols-2" onSubmit={submit} noValidate>
        {Object.keys(problems).length === 0 && <ErrorBanner error={error} />}
        {fields.map(([name, f]) => {
          const id = `action-field-${name}`;
          const value = answers[name];
          const set = (v: unknown) => setAnswers({ ...answers, [name]: v });
          return (
            <div className="field" key={name}>
              {f.type !== "boolean" && (
                <div className="label-row">
                  <label htmlFor={id}>{f.title ?? name}</label>
                  {required.has(name) && (
                    <span aria-hidden className="required">
                      *
                    </span>
                  )}
                </div>
              )}
              {f.type === "boolean" ? (
                <label className="check">
                  <input id={id} type="checkbox" checked={value === true} onChange={(e) => set(e.target.checked)} />
                  {f.title ?? name}
                </label>
              ) : f.enum.length > 0 ? (
                <select
                  id={id}
                  value={String(value ?? "")}
                  required={required.has(name)}
                  onChange={(e) => set(e.target.value || undefined)}
                >
                  <option value="">{t("Choose…")}</option>
                  {f.enum.map((o) => (
                    <option key={o} value={o}>
                      {o}
                    </option>
                  ))}
                </select>
              ) : (
                <input
                  id={id}
                  type={f.type === "integer" || f.type === "number" ? "number" : "text"}
                  step={f.type === "integer" ? 1 : undefined}
                  min={f.minimum ?? undefined}
                  max={f.maximum ?? undefined}
                  required={required.has(name)}
                  value={value == null ? "" : String(value)}
                  aria-invalid={!!problems[name]}
                  onChange={(e) => set(e.target.value === "" ? undefined : f.type === "string" ? e.target.value : Number(e.target.value))}
                />
              )}
              {problems[name] ? (
                <span className="field-error">{problems[name]}</span>
              ) : (
                f.description && <span className="hint">{f.description}</span>
              )}
            </div>
          );
        })}
      </form>
    </Dialog>
  );
}

type Job = Schemas["Job"];
type JobDownload = NonNullable<ActionResult["download"]>;

/**
 * Background actions (roadmap 3.11) the user started on this object in the last day: their
 * progress while they run, then the service's answer and file. Polls quickly only while one runs.
 */
export function BackgroundActions({ objectId, refreshKey }: { objectId: string; refreshKey: number }) {
  const qc = useQueryClient();
  const { data } = useQuery({
    queryKey: ["backgroundActions", objectId, refreshKey],
    queryFn: async () => {
      const jobs = await unwrap(api.GET("/api/v1/jobs", { params: { query: { type: "extensions.action", mine: true, limit: 30 } } }));
      const dayAgo = Date.now() - 86_400_000;
      return jobs.filter((j) => j.payload.objectId === objectId && new Date(j.createdAt).getTime() > dayAgo);
    },
    refetchInterval: (q) => (q.state.data?.some((j) => j.status === "QUEUED" || j.status === "RUNNING") ? 1000 : 30_000),
  });
  // When one ends, its notification is there: show it on the bell without waiting for its poll.
  const runningCount = data?.filter((j) => j.status === "QUEUED" || j.status === "RUNNING").length ?? 0;
  useEffect(() => {
    if (runningCount === 0) void qc.invalidateQueries({ queryKey: ["me", "notifications"] });
  }, [runningCount, qc]);
  const [hidden, setHidden] = useState<Set<string>>(new Set());
  const cancel = useMutation({
    mutationFn: (id: string) => unwrap(api.POST("/api/v1/jobs/{id}/cancel", { params: { path: { id } } })),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["backgroundActions", objectId] }),
  });
  const shown = (data ?? []).filter((j) => !hidden.has(j.id)).slice(0, 3);
  if (!shown.length) return null;
  return (
    <div className="bg-actions" aria-label={t("Background actions")}>
      {shown.map((j) => (
        <BackgroundJob key={j.id} job={j} onCancel={() => cancel.mutate(j.id)} onHide={() => setHidden(new Set(hidden).add(j.id))} />
      ))}
    </div>
  );
}

function BackgroundJob({ job, onCancel, onHide }: { job: Job; onCancel: () => void; onHide: () => void }) {
  const running = job.status === "QUEUED" || job.status === "RUNNING";
  const download = job.result?.download as JobDownload | undefined;
  return (
    <section className={`bg-action ${job.status.toLowerCase()}`} aria-label={job.title}>
      <div className="row" style={{ gap: 8 }}>
        {running ? (
          <Loader size={15} className="spin icon-muted" aria-hidden />
        ) : job.status === "SUCCEEDED" ? (
          <CircleCheck size={15} className="ok-icon" aria-hidden />
        ) : (
          <CircleAlert size={15} className="error-icon" aria-hidden />
        )}
        <strong>{job.title}</strong>
        <JobStatusTag job={job} />
        <span className="muted small">
          {running
            ? t("for {duration}", { duration: duration(job.startedAt ?? job.createdAt) })
            : t("took {duration}", { duration: duration(job.startedAt, job.finishedAt) })}
        </span>
        <span className="grow" />
        {running ? (
          <button className="btn small" onClick={onCancel} disabled={job.cancelRequested}>
            {t("Cancel")}
          </button>
        ) : (
          <button className="icon-btn" aria-label={t("Dismiss")} onClick={onHide}>
            <X size={13} />
          </button>
        )}
      </div>
      {running && <JobProgress job={job} />}
      {job.status === "SUCCEEDED" && (
        <div className="row" style={{ gap: 8 }}>
          <span>{String(job.result?.message ?? t("Done"))}</span>
          {download && (
            <button className="btn small" onClick={() => save(download)}>
              <Download size={13} aria-hidden /> {download.filename}
            </button>
          )}
        </div>
      )}
      {job.status === "FAILED" && <span className="error-text">{job.error}</span>}
      {job.status === "CANCELLED" && <span className="muted">{t("Cancelled.")}</span>}
    </section>
  );
}
