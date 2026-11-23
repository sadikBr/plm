import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowRight, CheckCircle2, FileArchive, Play, Trash2, TriangleAlert, Upload } from "lucide-react";
import { useMemo, useState } from "react";
import { Link } from "react-router";
import { ApiError, api, authorizedFetch, unwrap, type Schemas } from "../api/client";
import { useContextsOfKind, useLifecycles, useTypes } from "../api/queries";
import { pickLabel } from "../components/AttributeForm";
import { EmptyState, ErrorBanner, Loading, Panel, When, typeLabel } from "../components/common";
import { JobProgress, JobStatusTag } from "../components/JobProgress";
import { t, tn } from "../i18n/i18n";

type Migration = Schemas["Migration"];
type Mappings = Schemas["MigrationMappings"];

/** Migrations from other PLM systems (roadmap 9.3): upload a package, map, check, run. */
export function MigrationAdmin() {
  const { data: list, isLoading } = useQuery({ queryKey: ["migrations"], queryFn: () => unwrap(api.GET("/api/v1/migrations")) });
  const [selected, setSelected] = useState<string | null>(null);
  const current = list?.find((m) => m.id === selected) ?? list?.[0];
  if (isLoading) return <Loading />;
  return (
    <div className="stack">
      <NewMigration onCreated={setSelected} />
      {list && list.length > 1 && (
        <div className="chips" role="tablist" aria-label={t("Migrations")}>
          {list.map((m) => (
            <button
              key={m.id}
              role="tab"
              aria-selected={current?.id === m.id}
              className={current?.id === m.id ? "chip active" : "chip"}
              onClick={() => setSelected(m.id)}
            >
              {m.name}
            </button>
          ))}
        </div>
      )}
      {current ? (
        <MigrationDetails key={current.id} migration={current} />
      ) : (
        <div className="panel">
          <EmptyState
            icon={<FileArchive size={20} aria-hidden />}
            title={t("No migrations yet")}
            hint={t("Export the other system's data as a package (a zip with manifest.json and the files), then upload it here.")}
          />
        </div>
      )}
    </div>
  );
}

function NewMigration({ onCreated }: { onCreated: (id: string) => void }) {
  const qc = useQueryClient();
  const { data: products } = useContextsOfKind("PRODUCT");
  const { data: libraries } = useContextsOfKind("LIBRARY");
  const targets = [...(products ?? []), ...(libraries ?? [])];
  const [contextId, setContextId] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const upload = useMutation({
    mutationFn: async () => {
      const form = new FormData();
      form.append("package", file!, file!.name);
      const r = await authorizedFetch(`/api/v1/migrations?contextId=${contextId || targets[0]?.id}`, { method: "POST", body: form });
      const body = await r.json().catch(() => undefined);
      if (!r.ok) throw new ApiError(r.status, body as never);
      return body as Migration;
    },
    onSuccess: (m) => {
      void qc.invalidateQueries({ queryKey: ["migrations"] });
      onCreated(m.id);
      setFile(null);
    },
  });
  return (
    <Panel title={t("New migration")} note={t("A zip with manifest.json (format plm-migration/1) and the files it names")}>
      <ErrorBanner error={upload.error} />
      <div className="row migration-new">
        <label className="file-drop small">
          <input type="file" accept=".zip" aria-label={t("Migration package")} onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
          <FileArchive size={18} aria-hidden />
          <span>{file ? file.name : t("Choose the package (.zip)")}</span>
        </label>
        <ArrowRight size={16} aria-hidden className="muted" />
        <select aria-label={t("Into")} value={contextId} onChange={(e) => setContextId(e.target.value)}>
          {targets.map((c) => (
            <option key={c.id} value={c.id}>
              {c.name}
            </option>
          ))}
        </select>
        <button className="btn primary" disabled={!file || upload.isPending || targets.length === 0} onClick={() => upload.mutate()}>
          <Upload size={14} aria-hidden /> {t("Upload and check")}
        </button>
      </div>
    </Panel>
  );
}

function MigrationDetails({ migration: m }: { migration: Migration }) {
  const qc = useQueryClient();
  const { data: types } = useTypes();
  const { data: lifecycles } = useLifecycles();
  const [mappings, setMappings] = useState<Mappings>(m.mappings);
  const states = useMemo(() => {
    const keys = new Map<string, Record<string, string>>();
    for (const l of lifecycles ?? []) for (const s of l.states) if (!keys.has(s.key)) keys.set(s.key, s.labels);
    return [...keys.entries()];
  }, [lifecycles]);
  const refresh = () => void qc.invalidateQueries({ queryKey: ["migrations"] });
  const save = useMutation({
    mutationFn: (next: Mappings) => unwrap(api.PUT("/api/v1/migrations/{id}/mappings", { params: { path: { id: m.id } }, body: next })),
    onSuccess: refresh,
  });
  const run = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/migrations/{id}/run", { params: { path: { id: m.id } } })),
    onSuccess: refresh,
  });
  const remove = useMutation({
    mutationFn: () => unwrap(api.DELETE("/api/v1/migrations/{id}", { params: { path: { id: m.id } } })),
    onSuccess: refresh,
  });
  const jobId = run.data?.id ?? m.lastJobId;
  const { data: job } = useQuery({
    queryKey: ["jobs", jobId],
    queryFn: () => unwrap(api.GET("/api/v1/jobs/{id}", { params: { path: { id: jobId! } } })),
    enabled: !!jobId,
    refetchInterval: (q) => {
      const s = q.state.data?.status;
      if (s && s !== "QUEUED" && s !== "RUNNING") {
        refresh();
        void qc.invalidateQueries({ queryKey: ["migrationItems", m.id] });
        return false;
      }
      return 1000;
    },
  });
  const { data: items } = useQuery({
    queryKey: ["migrationItems", m.id],
    queryFn: () => unwrap(api.GET("/api/v1/migrations/{id}/items", { params: { path: { id: m.id } } })),
  });
  const a = m.analysis;
  const set = (kind: "types" | "states" | "attributes" | "users", from: string, to: string) => {
    const next = { ...mappings, [kind]: { ...(mappings[kind] ?? {}), [from]: to } };
    setMappings(next);
    save.mutate(next);
  };
  const running = job?.status === "QUEUED" || job?.status === "RUNNING";
  const creatable = (types ?? []).filter((ty) => ty.instantiable);
  const mappedRows = (kind: "types" | "states" | "attributes" | "users") => Object.keys(mappings[kind] ?? {});
  const typeRows = [...new Set([...Object.keys(a.unmappedTypes), ...mappedRows("types")])];
  const stateRows = [...new Set([...Object.keys(a.unmappedStates), ...mappedRows("states")])];
  const attributeRows = [...new Set([...Object.keys(a.unmappedAttributes), ...mappedRows("attributes")])];
  return (
    <>
      <Panel
        title={m.name}
        note={`${a.source ?? m.fileName} → ${m.contextName}`}
        actions={
          <>
            {job && <JobStatusTag job={job} />}
            <button className="btn small primary" disabled={a.errors > 0 || running || run.isPending} onClick={() => run.mutate()}>
              <Play size={13} aria-hidden /> {m.done > 0 ? t("Run again") : t("Run")}
            </button>
            <button className="icon-btn" aria-label={t("Delete the migration")} disabled={running} onClick={() => remove.mutate()}>
              <Trash2 size={14} />
            </button>
          </>
        }
      >
        <ErrorBanner error={run.error ?? save.error ?? remove.error} />
        <div className="migration-facts">
          <Fact n={a.objects} label={t("objects")} />
          <Fact n={a.iterations} label={t("iterations")} />
          <Fact n={a.files} label={t("files")} />
          <Fact n={a.usages} label={t("structure lines")} />
          <Fact n={a.documentLinks} label={t("document links")} />
          <Fact n={a.users} label={t("people")} />
          <Fact n={m.done} label={t("brought in")} tone="ok" />
          {m.failed > 0 && <Fact n={m.failed} label={t("failed")} tone="bad" />}
        </div>
        {job && running && <JobProgress job={job} />}
        <div className={a.errors ? "banner error" : "banner ok"}>
          {a.errors ? <TriangleAlert size={15} aria-hidden /> : <CheckCircle2 size={15} aria-hidden />}{" "}
          {a.errors
            ? tn(
                "{n} problem stops the run: map what isn't matched below.",
                "{n} problems stop the run: map what isn't matched below.",
                a.errors,
              )
            : a.alreadyDone === a.objects
              ? t("Everything in the package has been brought in.")
              : t("Ready: {n} objects keep their numbers, versions, dates and authors.", { n: a.objects - a.alreadyDone })}
        </div>
      </Panel>
      <Panel title={t("Numbers already here")} note={t("For a package received back")}>
        <div className="row migration-existing">
          <select
            aria-label={t("Numbers already here")}
            value={mappings.existing ?? "STOP"}
            onChange={(e) => {
              const next = { ...mappings, existing: e.target.value };
              setMappings(next);
              save.mutate(next);
            }}
          >
            <option value="STOP">{t("Stop: report them as problems")}</option>
            <option value="COPY">{t("Bring them in as copies with a suffix")}</option>
            <option value="UPDATE">{t("Update them: a new iteration each")}</option>
          </select>
          {mappings.existing === "COPY" && (
            <input
              aria-label={t("Suffix")}
              defaultValue={mappings.copySuffix ?? ""}
              placeholder={t("Suffix, e.g. ACME")}
              onBlur={(e) => {
                const next = { ...mappings, copySuffix: e.target.value };
                setMappings(next);
                save.mutate(next);
              }}
            />
          )}
        </div>
      </Panel>
      <div className="migration-maps">
        <Panel title={t("Types")} flush>
          <MapTable
            rows={typeRows}
            counts={a.unmappedTypes}
            value={(k) => mappings.types?.[k] ?? ""}
            onChange={(k, v) => set("types", k, v)}
            options={creatable.map((ty) => [ty.name, typeLabel(ty)])}
            empty={t("Every type matches.")}
          />
        </Panel>
        <Panel title={t("States")} flush>
          <MapTable
            rows={stateRows}
            counts={a.unmappedStates}
            value={(k) => mappings.states?.[k] ?? ""}
            onChange={(k, v) => set("states", k, v)}
            options={states.map(([key, labels]) => [key, pickLabel(labels, key)])}
            empty={t("Every state matches.")}
          />
        </Panel>
        <Panel title={t("Attributes")} note={t("Leave empty to leave the values out")} flush>
          <MapTable
            rows={attributeRows}
            counts={a.unmappedAttributes}
            value={(k) => mappings.attributes?.[k] ?? ""}
            onChange={(k, v) => set("attributes", k, v)}
            free
            empty={t("Every attribute matches.")}
          />
        </Panel>
      </div>
      {a.issues.length > 0 && (
        <Panel title={t("Check")} note={tn("{n} remark", "{n} remarks", a.issues.length)} flush>
          <ul className="migration-issues">
            {a.issues.map((i, n) => (
              <li key={n} className={i.severity === "ERROR" ? "error" : "warning"}>
                <TriangleAlert size={13} aria-hidden /> {i.number && <strong>{i.number}</strong>} {i.message}
              </li>
            ))}
          </ul>
        </Panel>
      )}
      {items && items.length > 0 && (
        <Panel title={t("Objects")} note={tn("{n} object", "{n} objects", items.length)} flush>
          <div className="migration-items">
            <table className="grid" aria-label={t("Objects")}>
              <thead>
                <tr>
                  <th>{t("Number")}</th>
                  <th>{t("In the other system")}</th>
                  <th>{t("Status")}</th>
                  <th>{t("Remarks")}</th>
                </tr>
              </thead>
              <tbody>
                {items.map((i) => (
                  <tr key={i.legacyId}>
                    <td>{i.objectId ? <Link to={`/objects/${i.objectId}`}>{i.number}</Link> : i.number}</td>
                    <td className="muted small">{i.legacyId}</td>
                    <td>
                      <span className={i.status === "DONE" ? "tag allow" : i.status === "FAILED" ? "tag deny" : "tag warn"}>
                        {i.status === "DONE" ? t("brought in") : i.status === "FAILED" ? t("failed") : t("links pending")}
                      </span>
                    </td>
                    <td className="small">{i.message}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Panel>
      )}
      <p className="muted small">
        {t("Uploaded")} <When iso={m.createdAt} /> · {m.fileName}
      </p>
    </>
  );
}

function Fact({ n, label, tone }: { n: number; label: string; tone?: "ok" | "bad" }) {
  return (
    <span className={tone ? `migration-fact ${tone}` : "migration-fact"}>
      <strong>{n}</strong> {label}
    </span>
  );
}

function MapTable({
  rows,
  counts,
  value,
  onChange,
  options,
  free = false,
  empty,
}: {
  rows: string[];
  counts: Record<string, number>;
  value: (k: string) => string;
  onChange: (k: string, v: string) => void;
  options?: [string, string][];
  free?: boolean;
  empty: string;
}) {
  if (rows.length === 0) return <p className="empty">{empty}</p>;
  return (
    <table className="grid">
      <tbody>
        {rows.map((k) => (
          <tr key={k}>
            <td>
              <code>{k}</code>
              {counts[k] ? <span className="muted small"> ×{counts[k]}</span> : null}
            </td>
            <td className="nowrap" style={{ width: 24 }}>
              <ArrowRight size={13} aria-hidden className="muted" />
            </td>
            <td>
              {free ? (
                <input
                  aria-label={t("Map {name} to", { name: k })}
                  defaultValue={value(k)}
                  placeholder={t("left out")}
                  onBlur={(e) => e.target.value !== value(k) && onChange(k, e.target.value)}
                />
              ) : (
                <select aria-label={t("Map {name} to", { name: k })} value={value(k)} onChange={(e) => onChange(k, e.target.value)}>
                  <option value="">{t("Choose…")}</option>
                  {options?.map(([v, label]) => (
                    <option key={v} value={v}>
                      {label}
                    </option>
                  ))}
                </select>
              )}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
