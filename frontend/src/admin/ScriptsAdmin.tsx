import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { api, unwrap, type Schemas } from "../api/client";
import { useTypes } from "../api/queries";
import { ErrorBanner, Loading, Panel, PrincipalName, formatDate, typeLabel } from "../components/common";
import { t } from "../i18n/i18n";

type Script = Schemas["ServerScript"];
type Purpose = Script["purpose"];
type Run = Schemas["ScriptRun"];

const OPERATIONS = (): [string, string][] => [
  ["object.create", t("Create")],
  ["object.check-in", t("Check in")],
  ["object.set-state", t("Set state")],
  ["object.rename", t("Rename")],
  ["object.revise", t("Revise")],
  ["object.move", t("Move")],
  ["object.delete", t("Delete")],
];

const PURPOSES = (): [Purpose, string, string][] => [
  ["VALIDATION", t("Validation"), t("validate(ctx) returns a message (or a list) to refuse the operation; nothing lets it through.")],
  ["NUMBERING", t("Numbering"), t("number(ctx) returns the new object's number, or nothing to leave it to the numbering scheme.")],
  ["AUTOMATION", t("Automation step"), t("run(ctx) is a step of an automation and returns anything JSON.")],
];

const SAMPLES: Record<Purpose, { source: string; input: string }> = {
  VALIDATION: {
    source: `// ctx.object: the object (number, name, state, attributes…); ctx.user; ctx.operation; ctx.event
function validate(ctx) {
  if (ctx.operation === "object.check-in" && !ctx.object.attributes.material) {
    return "Give the part a material before checking it in";
  }
}`,
    input: `{"operation": "object.check-in", "object": {"number": "0000000042", "name": "Bracket", "attributes": {}}}`,
  },
  NUMBERING: {
    source: `// ctx.name, ctx.types, ctx.attributes, ctx.contextId, ctx.user
function number(ctx) {
  var prefix = ctx.types.indexOf("Fastener") >= 0 ? "FST" : "PRT";
  return prefix + "-" + ctx.name.toUpperCase().replace(/[^A-Z0-9]+/g, "-").slice(0, 20);
}`,
    input: `{"name": "Hex bolt M6", "types": ["Part", "Fastener"], "attributes": {}}`,
  },
  AUTOMATION: {
    source: `// plm.find(number), plm.object(id) read as the user; plm.log(...) shows in the run
function run(ctx) {
  var part = plm.find(ctx.number);
  plm.log("found", part && part.name);
  return { heavy: !!part && Number(part.attributes.mass || 0) > 10 };
}`,
    input: `{"number": "0000000042"}`,
  },
};

/** Sandboxed server scripts (roadmap 5.14). */
export function ScriptsAdmin() {
  const {
    data: scripts,
    isLoading,
    error,
  } = useQuery({ queryKey: ["serverScripts"], queryFn: () => unwrap(api.GET("/api/v1/server-scripts")) });
  const [selected, setSelected] = useState<string | "new" | null>(null);
  if (isLoading) return <Loading />;
  const current = selected === "new" ? null : (scripts ?? []).find((s) => s.id === selected);
  return (
    <div className="reports-layout">
      <Panel
        title={t("Server scripts")}
        flush
        actions={
          <button className="btn small" onClick={() => setSelected("new")}>
            {t("+ Script")}
          </button>
        }
      >
        <ErrorBanner error={error} />
        {!scripts?.length ? (
          <p className="empty">{t("No scripts yet.")}</p>
        ) : (
          <ul className="report-list">
            {scripts.map((s) => (
              <li key={s.id} className={s.id === selected ? "selected" : ""}>
                <a
                  href="#"
                  onClick={(e) => {
                    e.preventDefault();
                    setSelected(s.id);
                  }}
                >
                  {s.name}
                </a>
                <span className="muted small">
                  {PURPOSES().find(([p]) => p === s.purpose)?.[1]}
                  {!s.active && ` · ${t("off")}`}
                </span>
              </li>
            ))}
          </ul>
        )}
        <p className="hint" style={{ padding: "8px 12px" }}>
          {t(
            "Each run has a time limit, 5 million instructions, 64 MB of memory and 25 calls to plm. Scripts see no Java, files or network.",
          )}
        </p>
      </Panel>
      {selected ? (
        <ScriptEditor key={selected} script={current ?? null} onSaved={(id) => setSelected(id)} onDeleted={() => setSelected(null)} />
      ) : (
        <Panel title={t("Scripts")}>
          <p className="muted">{t("Choose a script, or add one: validation, numbering or an automation step, in JavaScript.")}</p>
        </Panel>
      )}
    </div>
  );
}

function ScriptEditor({ script, onSaved, onDeleted }: { script: Script | null; onSaved: (id: string) => void; onDeleted: () => void }) {
  const qc = useQueryClient();
  const { data: types } = useTypes();
  const [name, setName] = useState(script?.name ?? "");
  const [purpose, setPurpose] = useState<Purpose>(script?.purpose ?? "VALIDATION");
  const [source, setSource] = useState(script?.source ?? SAMPLES.VALIDATION.source);
  const [description, setDescription] = useState(script?.description ?? "");
  const [typeNames, setTypeNames] = useState<string[]>(script?.types ?? []);
  const [operations, setOperations] = useState<string[]>(script?.operations ?? ["object.check-in"]);
  const [timeoutMs, setTimeoutMs] = useState(String(script?.timeoutMs ?? 250));
  const [active, setActive] = useState(script?.active ?? true);
  const [input, setInput] = useState(SAMPLES[script?.purpose ?? "VALIDATION"].input);
  const settings = () => ({
    name,
    purpose,
    source,
    description: description || null,
    types: typeNames,
    operations: purpose === "VALIDATION" ? operations : [],
    active,
    timeoutMs: Number(timeoutMs) || 250,
  });
  const save = useMutation({
    mutationFn: () =>
      script
        ? unwrap(api.PUT("/api/v1/server-scripts/{id}", { params: { path: { id: script.id } }, body: settings() }))
        : unwrap(api.POST("/api/v1/server-scripts", { body: settings() })),
    onSuccess: (s) => {
      void qc.invalidateQueries({ queryKey: ["serverScripts"] });
      onSaved(s.id);
    },
  });
  const remove = useMutation({
    mutationFn: () => unwrap(api.DELETE("/api/v1/server-scripts/{id}", { params: { path: { id: script!.id } } })),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["serverScripts"] });
      onDeleted();
    },
  });
  const test = useMutation({
    mutationFn: () => {
      let parsed: Record<string, unknown>;
      try {
        parsed = JSON.parse(input || "{}") as Record<string, unknown>;
      } catch {
        throw new Error(t("The sample input isn't valid JSON"));
      }
      return unwrap(api.POST("/api/v1/server-scripts/test", { body: { input: parsed, source, purpose } }));
    },
  });
  const pickPurpose = (p: Purpose) => {
    setPurpose(p);
    if (!script) {
      setSource(SAMPLES[p].source);
      setInput(SAMPLES[p].input);
    }
  };
  const toggle = (list: string[], set: (v: string[]) => void, v: string) =>
    set(list.includes(v) ? list.filter((x) => x !== v) : [...list, v]);
  const hint = PURPOSES().find(([p]) => p === purpose)?.[2];
  return (
    <div className="details-stack">
      <Panel
        title={script ? script.name : t("New script")}
        note={
          script ? (
            <>
              {t("Changed {date} by", { date: formatDate(script.updatedAt) })} <PrincipalName id={script.updatedBy} />
            </>
          ) : undefined
        }
        actions={
          script && (
            <button className="btn small ghost" onClick={() => remove.mutate()}>
              {t("Delete")}
            </button>
          )
        }
      >
        <div className="attr-form cols-3">
          <div className="field">
            <label htmlFor="sc-name">{t("Name")}</label>
            <input id="sc-name" value={name} onChange={(e) => setName(e.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="sc-purpose">{t("Purpose")}</label>
            <select id="sc-purpose" value={purpose} onChange={(e) => pickPurpose(e.target.value as Purpose)}>
              {PURPOSES().map(([p, label]) => (
                <option key={p} value={p}>
                  {label}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor="sc-timeout">{t("Time limit (ms)")}</label>
            <input
              id="sc-timeout"
              inputMode="numeric"
              value={timeoutMs}
              onChange={(e) => setTimeoutMs(e.target.value.replace(/\D/g, ""))}
            />
          </div>
          <div className="field wide">
            <label htmlFor="sc-desc">{t("Description")}</label>
            <input id="sc-desc" value={description} onChange={(e) => setDescription(e.target.value)} />
          </div>
        </div>
        <fieldset className="check-list scroll-list">
          <legend>{t("For types (none: all)")}</legend>
          {(types ?? [])
            .filter((x) => x.baseClass === "PART" || x.baseClass === "DOCUMENT" || x.baseClass === "CHANGE")
            .sort((x, y) => Number(typeNames.includes(y.name)) - Number(typeNames.includes(x.name)))
            .map((x) => (
              <label key={x.id} className="check">
                <input type="checkbox" checked={typeNames.includes(x.name)} onChange={() => toggle(typeNames, setTypeNames, x.name)} />
                {typeLabel(x)}
              </label>
            ))}
        </fieldset>
        {purpose === "VALIDATION" && (
          <fieldset className="check-list">
            <legend>{t("Checks")}</legend>
            {OPERATIONS().map(([key, label]) => (
              <label key={key} className="check">
                <input type="checkbox" checked={operations.includes(key)} onChange={() => toggle(operations, setOperations, key)} />
                {label}
              </label>
            ))}
          </fieldset>
        )}
        <div className="field">
          <label htmlFor="sc-source">{t("Script (JavaScript)")}</label>
          <textarea
            id="sc-source"
            className="code script-source"
            rows={12}
            spellCheck={false}
            value={source}
            onChange={(e) => setSource(e.target.value)}
          />
          {hint && <span className="hint">{hint}</span>}
        </div>
        <div className="row" style={{ gap: 8, marginTop: 10 }}>
          <label className="check">
            <input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} />
            {t("On")}
          </label>
          <button className="btn primary small" disabled={save.isPending || !name.trim()} onClick={() => save.mutate()}>
            {t("Save")}
          </button>
          {save.isSuccess && <span className="muted">{t("Saved.")}</span>}
        </div>
        <ErrorBanner error={save.error ?? remove.error} />
      </Panel>
      <Panel title={t("Try it")} note={t("Runs the script above on this input as ctx. Nothing is saved or changed.")}>
        <div className="field">
          <label htmlFor="sc-input">{t("Sample ctx (JSON)")}</label>
          <textarea id="sc-input" className="code" rows={3} spellCheck={false} value={input} onChange={(e) => setInput(e.target.value)} />
        </div>
        <button className="btn small" style={{ marginTop: 8 }} disabled={test.isPending} onClick={() => test.mutate()}>
          {t("Run")}
        </button>
        <ErrorBanner error={test.error} />
        {test.data && <RunResult run={test.data} />}
      </Panel>
      {script && <RunsPanel scriptId={script.id} purpose={script.purpose} />}
    </div>
  );
}

function RunResult({ run }: { run: Run }) {
  return (
    <div className="script-result">
      <div className={`banner ${run.ok ? "ok" : "error"}`}>
        {run.ok
          ? run.instructions > 0
            ? t("Ran in {ms} ms, {n} instructions.", { ms: run.durationMs, n: run.instructions })
            : t("Ran in {ms} ms.", { ms: run.durationMs })
          : run.error}
      </div>
      {run.ok && (
        <pre className="code-block">
          {run.result === null || run.result === undefined ? t("(nothing returned)") : JSON.stringify(run.result, null, 2)}
        </pre>
      )}
      {run.logs.length > 0 && (
        <>
          <div className="muted small">{t("Logged")}</div>
          <pre className="code-block">{run.logs.join("\n")}</pre>
        </>
      )}
    </div>
  );
}

function RunsPanel({ scriptId, purpose }: { scriptId: string; purpose: Purpose }) {
  const { data } = useQuery({
    queryKey: ["serverScriptRuns", scriptId],
    queryFn: () => unwrap(api.GET("/api/v1/server-scripts/{id}/runs", { params: { path: { id: scriptId } } })),
    refetchInterval: 10_000,
  });
  return (
    <Panel title={t("Recent runs")} flush>
      {!data?.length ? (
        <p className="empty">{t("It hasn't run yet.")}</p>
      ) : (
        <table className="grid">
          <thead>
            <tr>
              <th>{t("When")}</th>
              <th>{t("For")}</th>
              <th>{t("By")}</th>
              <th className="num">{t("ms")}</th>
              <th>{t("Outcome")}</th>
            </tr>
          </thead>
          <tbody>
            {data.map((r) => (
              <tr key={r.id ?? r.ranAt}>
                <td className="nowrap">{formatDate(r.ranAt)}</td>
                <td className="nowrap">
                  <code>{r.trigger}</code>
                </td>
                <td>
                  <PrincipalName id={r.actor} />
                </td>
                <td className="num">{r.durationMs}</td>
                <td>
                  {!r.ok ? (
                    <span className="tag deny">{r.error}</span>
                  ) : r.result === null || r.result === undefined ? (
                    <span className="tag allow">{purpose === "VALIDATION" ? t("passed") : t("nothing returned")}</span>
                  ) : purpose === "VALIDATION" ? (
                    <span className="tag warn">{t("refused: {message}", { message: String(r.result).slice(0, 80) })}</span>
                  ) : (
                    <code>{JSON.stringify(r.result).slice(0, 80)}</code>
                  )}
                  {r.logs.length > 0 && <span className="muted small"> · {r.logs.join(" | ").slice(0, 80)}</span>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Panel>
  );
}
