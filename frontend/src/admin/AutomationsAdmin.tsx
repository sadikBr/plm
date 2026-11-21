import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowDown, ArrowUp, Trash2 } from "lucide-react";
import { useState } from "react";
import { Link } from "react-router";
import { api, unwrap, type Schemas } from "../api/client";
import { useLifecycles, useTypes } from "../api/queries";
import { pickLabel } from "../components/AttributeForm";
import { ErrorBanner, Loading, Panel, PrincipalName, formatDate, typeLabel } from "../components/common";
import { t } from "../i18n/i18n";

type Automation = Schemas["Automation"];
type Action = Schemas["AutomationAction"];
type Kind = Action["kind"];

const KINDS = (): [Kind, string][] => [
  ["SET_STATE", t("Set the state")],
  ["SET_ATTRIBUTE", t("Set a value")],
  ["NOTIFY", t("Notify")],
  ["START_PROCESS", t("Start a process")],
  ["WEBHOOK", t("Call a webhook")],
  ["RUN_SCRIPT", t("Run a script")],
];
const kindName = (k: Kind) => KINDS().find(([x]) => x === k)?.[1] ?? k;

/** No-code automations (roadmap 5.16). */
export function AutomationsAdmin() {
  const { data, isLoading, error } = useQuery({ queryKey: ["automations"], queryFn: () => unwrap(api.GET("/api/v1/automations")) });
  const { data: triggers } = useQuery({ queryKey: ["automationTriggers"], queryFn: () => unwrap(api.GET("/api/v1/automation-triggers")) });
  const [selected, setSelected] = useState<string | "new" | null>(null);
  if (isLoading) return <Loading />;
  const current = (data ?? []).find((a) => a.id === selected) ?? null;
  const triggerLabel = (e: string) => triggers?.find((x) => x.event === e)?.label ?? e;
  return (
    <div className="reports-layout">
      <Panel
        title={t("Automations")}
        flush
        actions={
          <button className="btn small" onClick={() => setSelected("new")}>
            {t("+ Automation")}
          </button>
        }
      >
        <ErrorBanner error={error} />
        {!data?.length ? (
          <p className="empty">{t("No automations yet.")}</p>
        ) : (
          <ul className="report-list">
            {data.map((a) => (
              <li key={a.id} className={a.id === selected ? "selected" : ""}>
                <a
                  href="#"
                  onClick={(e) => {
                    e.preventDefault();
                    setSelected(a.id);
                  }}
                >
                  {a.name}
                </a>
                <span className="muted small">
                  {t(triggerLabel(a.event))}
                  {!a.active && ` · ${t("off")}`}
                </span>
              </li>
            ))}
          </ul>
        )}
        <p className="hint" style={{ padding: "8px 12px" }}>
          {t("They run after the change that triggers them is saved, as “automation”. Changes they make don't trigger automations.")}
        </p>
      </Panel>
      {selected ? (
        <AutomationEditor
          key={selected}
          automation={selected === "new" ? null : current}
          triggers={triggers ?? []}
          onSaved={setSelected}
          onDeleted={() => setSelected(null)}
        />
      ) : (
        <Panel title={t("Automations")}>
          <p className="muted">{t("When something happens to an object, and a condition holds, do something: no code needed.")}</p>
        </Panel>
      )}
    </div>
  );
}

function AutomationEditor({
  automation,
  triggers,
  onSaved,
  onDeleted,
}: {
  automation: Automation | null;
  triggers: Schemas["AutomationTrigger"][];
  onSaved: (id: string) => void;
  onDeleted: () => void;
}) {
  const qc = useQueryClient();
  const { data: types } = useTypes();
  const [name, setName] = useState(automation?.name ?? "");
  const [description, setDescription] = useState(automation?.description ?? "");
  const [event, setEvent] = useState(automation?.event ?? "versioning.ObjectStateChanged.v1");
  const [typeNames, setTypeNames] = useState<string[]>(automation?.types ?? []);
  const [condition, setCondition] = useState(automation?.condition ?? "");
  const [actions, setActions] = useState<Action[]>(automation?.actions ?? [{ kind: "NOTIFY", recipients: ["owner"], message: "" }]);
  const [active, setActive] = useState(automation?.active ?? true);
  const body = () => ({ name, description: description || null, event, types: typeNames, condition: condition || null, actions, active });
  const save = useMutation({
    mutationFn: () =>
      automation
        ? unwrap(api.PUT("/api/v1/automations/{id}", { params: { path: { id: automation.id } }, body: body() }))
        : unwrap(api.POST("/api/v1/automations", { body: body() })),
    onSuccess: (a) => {
      void qc.invalidateQueries({ queryKey: ["automations"] });
      onSaved(a.id);
    },
  });
  const remove = useMutation({
    mutationFn: () => unwrap(api.DELETE("/api/v1/automations/{id}", { params: { path: { id: automation!.id } } })),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["automations"] });
      onDeleted();
    },
  });
  const trigger = triggers.find((x) => x.event === event);
  const setAction = (i: number, a: Action) => setActions(actions.map((x, j) => (j === i ? a : x)));
  const move = (i: number, d: number) => {
    const next = [...actions];
    [next[i], next[i + d]] = [next[i + d]!, next[i]!];
    setActions(next);
  };
  const toggle = (v: string) => setTypeNames(typeNames.includes(v) ? typeNames.filter((x) => x !== v) : [...typeNames, v]);
  return (
    <div className="details-stack">
      <Panel
        title={automation ? automation.name : t("New automation")}
        note={
          automation ? (
            <>
              {t("Changed {date} by", { date: formatDate(automation.updatedAt) })} <PrincipalName id={automation.updatedBy} />
            </>
          ) : undefined
        }
        actions={
          automation && (
            <button className="btn small ghost" onClick={() => remove.mutate()}>
              {t("Delete")}
            </button>
          )
        }
      >
        <div className="attr-form cols-3">
          <div className="field">
            <label htmlFor="au-name">{t("Name")}</label>
            <input id="au-name" value={name} onChange={(e) => setName(e.target.value)} />
          </div>
          <div className="field wide">
            <label htmlFor="au-desc">{t("Description")}</label>
            <input id="au-desc" value={description} onChange={(e) => setDescription(e.target.value)} />
          </div>
        </div>
        <div className="automation-flow">
          <section>
            <h4>{t("When")}</h4>
            <select aria-label={t("When")} value={event} onChange={(e) => setEvent(e.target.value)}>
              {triggers.map((x) => (
                <option key={x.event} value={x.event}>
                  {t(x.label)}
                </option>
              ))}
            </select>
            <fieldset className="check-list scroll-list">
              <legend>{t("To objects of type (none: any)")}</legend>
              {(types ?? [])
                .filter((x) => x.baseClass !== "CONTEXT")
                .sort((x, y) => Number(typeNames.includes(y.name)) - Number(typeNames.includes(x.name)))
                .map((x) => (
                  <label key={x.id} className="check">
                    <input type="checkbox" checked={typeNames.includes(x.name)} onChange={() => toggle(x.name)} />
                    {typeLabel(x)}
                  </label>
                ))}
            </fieldset>
          </section>
          <section>
            <h4>{t("If")}</h4>
            <input
              aria-label={t("Condition")}
              className="mono"
              value={condition}
              placeholder="event.to == 'RELEASED' && object.attributes.mass > 10"
              onChange={(e) => setCondition(e.target.value)}
            />
            <span className="hint">
              {t("Optional, in CEL. Uses object, user and event")}
              {trigger?.fields.length ? `: ${trigger.fields.map((f) => `event.${f}`).join(", ")}` : ""}.
            </span>
          </section>
          <section>
            <h4>{t("Then")}</h4>
            <ol className="automation-actions">
              {actions.map((a, i) => (
                <li key={i}>
                  <div className="row" style={{ gap: 6 }}>
                    <span className="step-no">{i + 1}</span>
                    <select
                      aria-label={t("Action")}
                      value={a.kind}
                      onChange={(e) => setAction(i, { kind: e.target.value as Kind, recipients: [] })}
                    >
                      {KINDS().map(([k, label]) => (
                        <option key={k} value={k}>
                          {label}
                        </option>
                      ))}
                    </select>
                    <span style={{ flex: 1 }} />
                    <button className="btn ghost small" aria-label={t("Up")} disabled={i === 0} onClick={() => move(i, -1)}>
                      <ArrowUp size={13} aria-hidden />
                    </button>
                    <button
                      className="btn ghost small"
                      aria-label={t("Down")}
                      disabled={i === actions.length - 1}
                      onClick={() => move(i, 1)}
                    >
                      <ArrowDown size={13} aria-hidden />
                    </button>
                    <button
                      className="btn ghost small"
                      aria-label={t("Remove")}
                      disabled={actions.length === 1}
                      onClick={() => setActions(actions.filter((_, j) => j !== i))}
                    >
                      <Trash2 size={13} aria-hidden />
                    </button>
                  </div>
                  <ActionFields action={a} onChange={(x) => setAction(i, x)} />
                </li>
              ))}
            </ol>
            {actions.length < 10 && (
              <button
                className="btn small"
                onClick={() => setActions([...actions, { kind: "NOTIFY", recipients: ["owner"], message: "" }])}
              >
                {t("+ Action")}
              </button>
            )}
          </section>
        </div>
        <div className="row" style={{ gap: 8, marginTop: 12 }}>
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
      {automation && <AutomationRuns id={automation.id} />}
    </div>
  );
}

function ActionFields({ action: a, onChange }: { action: Action; onChange: (a: Action) => void }) {
  const { data: lifecycles } = useLifecycles();
  const { data: templates } = useQuery({
    queryKey: ["processTemplates"],
    queryFn: () => unwrap(api.GET("/api/v1/process-templates")),
    enabled: a.kind === "START_PROCESS",
  });
  const { data: scripts } = useQuery({
    queryKey: ["serverScripts"],
    queryFn: () => unwrap(api.GET("/api/v1/server-scripts")),
    enabled: a.kind === "RUN_SCRIPT",
  });
  const states = new Map<string, string>();
  for (const lc of lifecycles ?? []) for (const s of lc.states) if (!states.has(s.key)) states.set(s.key, pickLabel(s.labels, s.key));
  switch (a.kind) {
    case "SET_STATE":
      return (
        <select aria-label={t("State")} value={a.state ?? ""} onChange={(e) => onChange({ ...a, state: e.target.value })}>
          <option value="">{t("Choose…")}</option>
          {[...states.entries()].map(([k, label]) => (
            <option key={k} value={k}>
              {label}
            </option>
          ))}
        </select>
      );
    case "SET_ATTRIBUTE":
      return (
        <div className="row" style={{ gap: 6 }}>
          <input
            aria-label={t("Attribute")}
            placeholder={t("attribute, e.g. description")}
            value={a.attribute ?? ""}
            onChange={(e) => onChange({ ...a, attribute: e.target.value })}
          />
          <input
            aria-label={t("Value")}
            placeholder={t("value")}
            value={String(a.value ?? "")}
            onChange={(e) => onChange({ ...a, value: e.target.value })}
          />
        </div>
      );
    case "NOTIFY":
      return (
        <div style={{ display: "grid", gap: 6 }}>
          <input
            aria-label={t("Recipients")}
            placeholder="owner, actor, user:bob"
            value={(a.recipients ?? []).join(", ")}
            onChange={(e) =>
              onChange({
                ...a,
                recipients: e.target.value
                  .split(",")
                  .map((x) => x.trim())
                  .filter(Boolean),
              })
            }
          />
          <input
            aria-label={t("Message")}
            placeholder={t("e.g. ${object.number} is now ${event.to}")}
            value={a.message ?? ""}
            onChange={(e) => onChange({ ...a, message: e.target.value })}
          />
        </div>
      );
    case "START_PROCESS":
      return (
        <select
          aria-label={t("Process")}
          value={a.templateId ?? ""}
          onChange={(e) => onChange({ ...a, templateId: e.target.value || null })}
        >
          <option value="">{t("Choose…")}</option>
          {(templates ?? []).map((x) => (
            <option key={x.id} value={x.id}>
              {x.name}
            </option>
          ))}
        </select>
      );
    case "WEBHOOK":
      return (
        <div className="row" style={{ gap: 6 }}>
          <input
            aria-label={t("URL")}
            placeholder="https://…"
            value={a.url ?? ""}
            onChange={(e) => onChange({ ...a, url: e.target.value })}
          />
          <input
            aria-label={t("Signing secret")}
            type="password"
            autoComplete="off"
            placeholder={t("signing secret (optional)")}
            value={a.secret ?? ""}
            onChange={(e) => onChange({ ...a, secret: e.target.value })}
          />
        </div>
      );
    case "RUN_SCRIPT":
      return (
        <div className="row" style={{ gap: 6 }}>
          <select aria-label={t("Script")} value={a.scriptId ?? ""} onChange={(e) => onChange({ ...a, scriptId: e.target.value || null })}>
            <option value="">{t("Choose…")}</option>
            {(scripts ?? [])
              .filter((s) => s.purpose === "AUTOMATION")
              .map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
          </select>
          <Link to="/admin?tab=scripts" className="small">
            {t("Scripts")}
          </Link>
        </div>
      );
  }
}

function AutomationRuns({ id }: { id: string }) {
  const { data } = useQuery({
    queryKey: ["automationRuns", id],
    queryFn: () => unwrap(api.GET("/api/v1/automations/{id}/runs", { params: { path: { id } } })),
    refetchInterval: 5_000,
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
              <th>{t("Object")}</th>
              <th>{t("Outcome")}</th>
              <th>{t("Steps")}</th>
            </tr>
          </thead>
          <tbody>
            {data.map((r) => (
              <tr key={r.id}>
                <td className="nowrap">{formatDate(r.ranAt)}</td>
                <td className="nowrap">{r.objectId ? <Link to={`/objects/${r.objectId}`}>{r.objectNumber ?? r.objectId}</Link> : "—"}</td>
                <td>
                  <span className={`tag ${r.outcome === "SUCCEEDED" ? "allow" : "deny"}`}>
                    {r.outcome === "SUCCEEDED" ? t("Done") : t("Failed")}
                  </span>
                </td>
                <td>
                  <ol className="run-steps">
                    {r.steps.map((s, i) => (
                      <li key={i} className={s.ok ? "" : "failed"}>
                        <strong>{kindName(s.kind)}</strong> — {s.message}
                      </li>
                    ))}
                  </ol>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Panel>
  );
}
