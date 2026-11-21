import { t } from "../i18n/i18n";
import type { Schemas } from "../api/client";

type Job = Schemas["Job"];

const STATUS_CLASS: Record<string, string> = {
  QUEUED: "tag",
  RUNNING: "tag accent",
  SUCCEEDED: "tag allow",
  FAILED: "tag deny",
  CANCELLED: "tag warn",
};

function statusLabel(status: string): string {
  switch (status) {
    case "QUEUED":
      return t("queued");
    case "RUNNING":
      return t("running");
    case "SUCCEEDED":
      return t("succeeded");
    case "FAILED":
      return t("failed");
    case "CANCELLED":
      return t("cancelled");
  }
  return status.toLowerCase();
}

export function JobStatusTag({ job }: { job: Pick<Job, "status" | "cancelRequested"> }) {
  return (
    <span className={STATUS_CLASS[job.status] ?? "tag"}>
      {job.status === "RUNNING" && job.cancelRequested ? t("cancelling") : statusLabel(job.status)}
    </span>
  );
}

/** A job's progress: a bar when it knows how much there is, its latest message under it. */
export function JobProgress({ job }: { job: Job }) {
  const p = job.progress;
  const pct =
    job.status === "SUCCEEDED" ? 100 : p?.total ? Math.min(100, Math.round((p.done / p.total) * 100)) : job.status === "RUNNING" ? null : 0;
  return (
    <div className="job-progress">
      <div
        className={`bar ${job.status.toLowerCase()} ${pct === null ? "indeterminate" : ""}`}
        role="progressbar"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={pct ?? undefined}
      >
        <span style={{ width: `${pct ?? 30}%` }} />
      </div>
      <span className="muted small">
        {p ? (p.total ? `${p.done} / ${p.total}` : `${p.done}`) : job.status === "QUEUED" ? t("Waiting") : ""}
        {p?.message ? ` · ${p.message}` : ""}
      </span>
    </div>
  );
}

/** "12 s", "3 min", "1 h 5 min" */
export function duration(from?: string | null, to?: string | null): string {
  if (!from) return "—";
  const ms = (to ? new Date(to).getTime() : Date.now()) - new Date(from).getTime();
  const s = Math.max(0, Math.round(ms / 1000));
  if (s < 60) return `${s} s`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m} min`;
  return `${Math.floor(m / 60)} h ${m % 60} min`;
}
