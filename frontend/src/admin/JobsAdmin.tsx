import { CalendarClock, Pencil, Play, Plus, RotateCcw, Square, Trash2 } from "lucide-react";
import { useState } from "react";
import { Dialog, ErrorBanner, Loading, Panel, PrincipalName, formatDate } from "../components/common";
import { JobProgress, JobStatusTag, duration } from "../components/JobProgress";
import {
  useCancelJob,
  useDeleteSchedule,
  useJobLog,
  useJobSchedules,
  useJobStats,
  useJobTypes,
  useJobs,
  useRetryJob,
  useRunSchedule,
  useSaveSchedule,
  useSubmitJob,
  type Job,
  type JobSchedule,
} from "./adminQueries";

const STATUSES: [string, string][] = [
  ["QUEUED", "Queued"],
  ["RUNNING", "Running"],
  ["SUCCEEDED", "Succeeded"],
  ["FAILED", "Failed"],
  ["CANCELLED", "Cancelled"],
];

/**
 * Background jobs (roadmap 3.1): what's queued, running and done, live; schedules that start jobs
 * on their own.
 */
export function JobsAdmin() {
  const [status, setStatus] = useState("");
  const [type, setType] = useState("");
  const [selected, setSelected] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);
  const { data: stats } = useJobStats();
  const { data: types } = useJobTypes();
  const { data: jobs, isLoading, error } = useJobs(status, type);
  const cancel = useCancelJob();
  const retry = useRetryJob();
  const typeLabel = new Map((types ?? []).map((t) => [t.type, t.label]));
  const current = jobs?.find((j) => j.id === selected);

  return (
    <div style={{ display: "grid", gap: 12 }}>
      <div className="job-stats" role="group" aria-label="Jobs by status">
        {STATUSES.map(([key, label]) => (
          <button
            key={key}
            className={`stat ${status === key ? "active" : ""}`}
            onClick={() => setStatus(status === key ? "" : key)}
            title={status === key ? "Show all" : `Show ${label.toLowerCase()} jobs`}
          >
            <strong>{stats?.[key] ?? 0}</strong>
            <span className="muted small">{label}</span>
          </button>
        ))}
      </div>
      <Panel
        title="Jobs"
        note="Refreshed every 2 seconds. Failures are retried with growing waits until a job's last attempt"
        flush
        actions={
          <>
            <select className="filter" aria-label="Job type" value={type} onChange={(e) => setType(e.target.value)}>
              <option value="">All types</option>
              {types?.map((t) => (
                <option key={t.type} value={t.type}>
                  {t.label}
                </option>
              ))}
            </select>
            <button className="btn small primary" onClick={() => setStarting(true)}>
              <Play size={13} aria-hidden />
              Start a job
            </button>
          </>
        }
      >
        <ErrorBanner error={error ?? cancel.error ?? retry.error} />
        {isLoading ? (
          <Loading />
        ) : !jobs?.length ? (
          <p className="empty">No jobs{status || type ? " match" : " yet"}.</p>
        ) : (
          <table className="grid" aria-label="Jobs">
            <thead>
              <tr>
                <th>Job</th>
                <th>Status</th>
                <th>Progress</th>
                <th>Attempts</th>
                <th>Runs as</th>
                <th>Queued</th>
                <th>Took</th>
                <th aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {jobs.map((j) => (
                <tr key={j.id} className={`clickable ${selected === j.id ? "selected" : ""}`} onClick={() => setSelected(j.id)}>
                  <td>
                    <strong>{j.title}</strong>
                    <div className="muted small">
                      {typeLabel.get(j.type) ?? j.type}
                      {j.scheduleId && (
                        <>
                          {" "}
                          · <CalendarClock size={11} aria-hidden /> scheduled
                        </>
                      )}
                    </div>
                  </td>
                  <td>
                    <JobStatusTag job={j} />
                  </td>
                  <td>
                    <JobProgress job={j} />
                  </td>
                  <td className="nowrap">
                    {j.attempts} of {j.maxAttempts}
                  </td>
                  <td className="nowrap">{j.submittedBy === "system" ? "System" : <PrincipalName id={j.submittedBy} />}</td>
                  <td className="nowrap muted">{formatDate(j.createdAt)}</td>
                  <td className="nowrap muted">{duration(j.startedAt, j.finishedAt)}</td>
                  <td className="nowrap" onClick={(e) => e.stopPropagation()}>
                    {(j.status === "QUEUED" || j.status === "RUNNING") && (
                      <button
                        className="icon-btn"
                        aria-label={`Cancel ${j.title}`}
                        title="Cancel"
                        disabled={cancel.isPending || j.cancelRequested}
                        onClick={() => cancel.mutate(j.id)}
                      >
                        <Square size={13} />
                      </button>
                    )}
                    {(j.status === "FAILED" || j.status === "CANCELLED") && (
                      <button
                        className="icon-btn"
                        aria-label={`Run ${j.title} again`}
                        title="Run again from the start"
                        disabled={retry.isPending}
                        onClick={() => retry.mutate(j.id)}
                      >
                        <RotateCcw size={13} />
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Panel>
      {current && <JobDetails job={current} typeLabel={typeLabel.get(current.type) ?? current.type} />}
      <Schedules typeLabel={(t) => typeLabel.get(t) ?? t} />
      {starting && <StartJobDialog onClose={() => setStarting(false)} onStarted={(id) => setSelected(id)} />}
    </div>
  );
}

function JobDetails({ job, typeLabel }: { job: Job; typeLabel: string }) {
  const live = job.status === "QUEUED" || job.status === "RUNNING";
  const { data: log } = useJobLog(job.id, live, `${job.status}:${job.attempts}`);
  return (
    <Panel title={job.title} note={`${typeLabel} · ${job.id}`}>
      <div style={{ display: "grid", gap: 10 }}>
        {job.error && <div className="banner error">{job.error}</div>}
        <div className="row wrap" style={{ gap: 24 }}>
          <div>
            <div className="muted small">Payload</div>
            <code className="wrap-code">{JSON.stringify(job.payload)}</code>
          </div>
          {job.result && (
            <div>
              <div className="muted small">Result</div>
              <code className="wrap-code">{JSON.stringify(job.result)}</code>
            </div>
          )}
          <div>
            <div className="muted small">Started</div>
            {job.startedAt ? formatDate(job.startedAt) : "—"}
          </div>
          <div>
            <div className="muted small">Ended</div>
            {job.finishedAt ? formatDate(job.finishedAt) : "—"}
          </div>
        </div>
        <pre className="job-log" aria-label="Job log">
          {log?.map((l, i) => (
            <div key={i} className={l.level}>
              {new Date(l.at).toLocaleTimeString()} {l.level.padEnd(5)} {l.message}
            </div>
          ))}
        </pre>
      </div>
    </Panel>
  );
}

function Schedules({ typeLabel }: { typeLabel: (type: string) => string }) {
  const { data: schedules } = useJobSchedules();
  const remove = useDeleteSchedule();
  const run = useRunSchedule();
  const [editing, setEditing] = useState<JobSchedule | "new" | null>(null);
  return (
    <Panel
      title="Schedules"
      note="Five-field cron: minute hour day month weekday"
      flush
      actions={
        <button className="btn small" onClick={() => setEditing("new")}>
          <Plus size={13} aria-hidden />
          New schedule
        </button>
      }
    >
      <ErrorBanner error={remove.error ?? run.error} />
      {!schedules?.length ? (
        <p className="empty">No schedules.</p>
      ) : (
        <table className="grid" aria-label="Schedules">
          <thead>
            <tr>
              <th>Schedule</th>
              <th>Runs</th>
              <th>When</th>
              <th>Next run</th>
              <th>Last run</th>
              <th aria-label="Actions" />
            </tr>
          </thead>
          <tbody>
            {schedules.map((s) => (
              <tr key={s.id} className={s.enabled ? undefined : "inactive"}>
                <td>
                  <strong>{s.name}</strong>
                  {!s.enabled && <div className="muted small">Off</div>}
                </td>
                <td>{typeLabel(s.type)}</td>
                <td className="nowrap">
                  <code>{s.cron}</code> <span className="muted small">{s.timeZone}</span>
                  <div className="muted small">{describeCron(s.cron)}</div>
                </td>
                <td className="nowrap muted">{s.enabled && s.nextRunAt ? formatDate(s.nextRunAt) : "—"}</td>
                <td className="nowrap">
                  {s.lastRunAt ? (
                    <>
                      <span className="muted">{formatDate(s.lastRunAt)}</span>{" "}
                      {s.lastJobStatus && <JobStatusTag job={{ status: s.lastJobStatus, cancelRequested: false }} />}
                    </>
                  ) : (
                    <span className="muted">Not yet</span>
                  )}
                </td>
                <td className="nowrap">
                  <button
                    className="icon-btn"
                    aria-label={`Run ${s.name} now`}
                    title="Run now"
                    disabled={run.isPending}
                    onClick={() => run.mutate(s.id)}
                  >
                    <Play size={13} />
                  </button>
                  <button className="icon-btn" aria-label={`Edit ${s.name}`} onClick={() => setEditing(s)}>
                    <Pencil size={13} />
                  </button>
                  <button
                    className="icon-btn"
                    aria-label={`Delete ${s.name}`}
                    onClick={() => window.confirm(`Delete the schedule “${s.name}”?`) && remove.mutate(s.id)}
                  >
                    <Trash2 size={13} />
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {editing && <ScheduleDialog schedule={editing === "new" ? null : editing} onClose={() => setEditing(null)} />}
    </Panel>
  );
}

/** A few common shapes in words; others are shown as they are. */
export function describeCron(cron: string): string {
  const [min, hour, day, month, weekday] = cron.trim().split(/\s+/);
  if (cron.startsWith("@")) return cron.slice(1);
  const at = (h: string, m: string) => `${h.padStart(2, "0")}:${m.padStart(2, "0")}`;
  if (min?.startsWith("*/") && hour === "*" && day === "*" && month === "*" && weekday === "*") return `Every ${min.slice(2)} minutes`;
  if (/^\d+$/.test(min ?? "") && hour === "*" && day === "*") return `Hourly at :${min!.padStart(2, "0")}`;
  if (/^\d+$/.test(min ?? "") && /^\d+$/.test(hour ?? "") && day === "*" && month === "*") {
    if (weekday === "*") return `Daily at ${at(hour!, min!)}`;
    if (weekday === "1-5") return `Weekdays at ${at(hour!, min!)}`;
  }
  return "";
}

function ScheduleDialog({ schedule, onClose }: { schedule: JobSchedule | null; onClose: () => void }) {
  const { data: types } = useJobTypes();
  const save = useSaveSchedule();
  const [name, setName] = useState(schedule?.name ?? "");
  const [type, setType] = useState(schedule?.type ?? "");
  const [cron, setCron] = useState(schedule?.cron ?? "0 2 * * *");
  const [timeZone, setTimeZone] = useState(schedule?.timeZone ?? Intl.DateTimeFormat().resolvedOptions().timeZone ?? "UTC");
  const [payload, setPayload] = useState(JSON.stringify(schedule?.payload ?? {}, null, 2));
  const [enabled, setEnabled] = useState(schedule?.enabled ?? true);
  const parsed = parseJson(payload);

  return (
    <Dialog
      open
      title={schedule ? `Edit ${schedule.name}` : "New schedule"}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button
            className="btn primary"
            disabled={save.isPending || !name.trim() || !type || !parsed.ok}
            onClick={() =>
              save.mutate(
                { id: schedule?.id, body: { name, type, cron, timeZone, payload: parsed.value ?? {}, enabled } },
                { onSuccess: onClose },
              )
            }
          >
            Save
          </button>
        </>
      }
    >
      <div className="attr-form">
        <ErrorBanner error={save.error} />
        <div className="field wide">
          <label htmlFor="sch-name">Name</label>
          <input id="sch-name" value={name} onChange={(e) => setName(e.target.value)} autoFocus />
        </div>
        <div className="field wide">
          <label htmlFor="sch-type">Job</label>
          <select id="sch-type" value={type} onChange={(e) => setType(e.target.value)}>
            <option value="">Choose…</option>
            {types?.map((t) => (
              <option key={t.type} value={t.type}>
                {t.label}
              </option>
            ))}
          </select>
          {types?.find((t) => t.type === type)?.description && (
            <span className="hint">{types.find((t) => t.type === type)!.description}</span>
          )}
        </div>
        <div className="field">
          <label htmlFor="sch-cron">When (cron)</label>
          <input id="sch-cron" className="code" value={cron} onChange={(e) => setCron(e.target.value)} />
          <span className="hint">{describeCron(cron) || "minute hour day month weekday, e.g. 30 6 * * 1-5"}</span>
        </div>
        <div className="field">
          <label htmlFor="sch-tz">Time zone</label>
          <input id="sch-tz" value={timeZone} onChange={(e) => setTimeZone(e.target.value)} />
        </div>
        <div className="field wide">
          <label htmlFor="sch-payload">Payload (JSON)</label>
          <textarea
            id="sch-payload"
            className="code"
            rows={4}
            value={payload}
            onChange={(e) => setPayload(e.target.value)}
            aria-invalid={!parsed.ok}
          />
          {!parsed.ok && <span className="hint bad">Not valid JSON: {parsed.error}</span>}
        </div>
        <label className="check wide">
          <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
          On
        </label>
      </div>
    </Dialog>
  );
}

function StartJobDialog({ onClose, onStarted }: { onClose: () => void; onStarted: (id: string) => void }) {
  const { data: types } = useJobTypes();
  const submit = useSubmitJob();
  const [type, setType] = useState("");
  const [payload, setPayload] = useState("{}");
  const parsed = parseJson(payload);
  return (
    <Dialog
      open
      title="Start a job"
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button
            className="btn primary"
            disabled={submit.isPending || !type || !parsed.ok}
            onClick={() =>
              submit.mutate(
                { type, payload: parsed.value ?? {} },
                {
                  onSuccess: (job) => {
                    onStarted(job.id);
                    onClose();
                  },
                },
              )
            }
          >
            Start
          </button>
        </>
      }
    >
      <div className="attr-form">
        <ErrorBanner error={submit.error} />
        <div className="field wide">
          <label htmlFor="job-type">Job</label>
          <select
            id="job-type"
            value={type}
            onChange={(e) => {
              setType(e.target.value);
              if (e.target.value === "system.test") setPayload('{\n  "steps": 20,\n  "stepMillis": 500\n}');
            }}
          >
            <option value="">Choose…</option>
            {types?.map((t) => (
              <option key={t.type} value={t.type}>
                {t.label}
              </option>
            ))}
          </select>
          {types?.find((t) => t.type === type)?.description && (
            <span className="hint">{types.find((t) => t.type === type)!.description}</span>
          )}
        </div>
        <div className="field wide">
          <label htmlFor="job-payload">Payload (JSON)</label>
          <textarea id="job-payload" className="code" rows={5} value={payload} onChange={(e) => setPayload(e.target.value)} />
          {!parsed.ok && <span className="hint bad">Not valid JSON: {parsed.error}</span>}
        </div>
      </div>
    </Dialog>
  );
}

function parseJson(text: string): { ok: boolean; value?: Record<string, unknown>; error?: string } {
  try {
    const v = JSON.parse(text || "{}") as unknown;
    if (typeof v !== "object" || v === null || Array.isArray(v)) return { ok: false, error: "an object is expected" };
    return { ok: true, value: v as Record<string, unknown> };
  } catch (e) {
    return { ok: false, error: (e as Error).message };
  }
}
