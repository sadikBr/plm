import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CircleAlert, CircleCheck, Plus, Save, Trash2 } from "lucide-react";
import { useEffect, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent } from "react";
import { api, unwrap, type Schemas } from "../api/client";
import { useLifecycles } from "../api/queries";
import { pickLabel } from "../components/AttributeForm";
import { ErrorBanner, Loading, Panel, formatDate } from "../components/common";
import { t, tn } from "../i18n/i18n";
import { AssignmentsAdmin } from "./Assignments";

type Template = Schemas["ProcessTemplate"];
type Definition = Schemas["ProcessDefinition"];
type Step = Schemas["ProcessNode"];
type Kind = Step["kind"];

/** Process templates (roadmap 5.2): a visual editor, checked as you go, saved as versions. */

const W = 150;
const H = 54;

const KINDS = (): { kind: Kind; label: string; hint: string }[] => [
  { kind: "TASK", label: t("Task"), hint: t("Someone decides") },
  { kind: "VOTE", label: t("Vote"), hint: t("Several people vote") },
  { kind: "ACTION", label: t("Action"), hint: t("The system does something") },
  { kind: "CONDITION", label: t("Condition"), hint: t("Goes one way or the other") },
  { kind: "PARALLEL", label: t("Parallel"), hint: t("Branches at the same time") },
  { kind: "JOIN", label: t("Join"), hint: t("Where branches meet") },
  { kind: "TIMER", label: t("Timer"), hint: t("Waits") },
  { kind: "END", label: t("End"), hint: t("Ends the process") },
];
const kindLabel = (k: Kind) => KINDS().find((x) => x.kind === k)?.label ?? k;

/** A step with the defaults the API fills in. */
const mk = (s: Partial<Step> & Pick<Step, "id" | "kind">): Step => ({
  assignees: [],
  branches: [],
  choices: [],
  outcomes: {},
  signature: false,
  escalateTo: [],
  ...s,
});

const BLANK: Definition = {
  start: "review",
  nodes: [
    mk({
      id: "review",
      kind: "TASK",
      label: "Review",
      assignees: ["role:MANAGER"],
      choices: ["APPROVE", "REJECT"],
      outcomes: { APPROVE: "approved", REJECT: "rejected" },
      x: 60,
      y: 120,
    }),
    mk({ id: "approved", kind: "END", label: "Approved", outcome: "APPROVED", x: 300, y: 50 }),
    mk({ id: "rejected", kind: "END", label: "Rejected", outcome: "REJECTED", x: 300, y: 190 }),
  ],
};

/** The ways out of a step: [label, target]. */
function exits(n: Step): [string, string][] {
  const out: [string, string][] = Object.entries(n.outcomes ?? {}).map(([k, v]) => [k, v]);
  if (n.next) out.push(["", n.next]);
  (n.branches ?? []).forEach((b, i) => out.push([`${i + 1}`, b]));
  if (n.join && n.kind === "PARALLEL") out.push([t("join"), n.join]);
  return out;
}

export function ProcessTemplatesAdmin() {
  const { data, error, isLoading } = useQuery({
    queryKey: ["processTemplates"],
    queryFn: () => unwrap(api.GET("/api/v1/process-templates")),
  });
  const [selected, setSelected] = useState<string | null>(null);
  const current = selected === "new" ? null : (data?.find((x) => x.id === selected) ?? data?.[0] ?? null);
  return (
    <div style={{ display: "grid", gap: 16 }}>
      <div className="split-admin">
        <Panel
          title={t("Process templates")}
          flush
          actions={
            <button className="btn small" onClick={() => setSelected("new")}>
              <Plus size={13} aria-hidden /> {t("New")}
            </button>
          }
        >
          <ErrorBanner error={error} />
          {isLoading ? (
            <Loading />
          ) : (
            <ul className="report-list" aria-label={t("Process templates")}>
              {data?.map((x) => (
                <li key={x.id}>
                  <button
                    className={x.id === current?.id && selected !== "new" ? "link-btn on" : "link-btn"}
                    onClick={() => setSelected(x.id)}
                  >
                    {x.name}
                  </button>
                  <span className="muted small">
                    {" "}
                    v{x.version}
                    {!x.active && ` · ${t("not in use")}`}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </Panel>
        {(current || selected === "new") && (
          <TemplateEditor
            key={selected === "new" ? "new" : `${current!.id}:${current!.version}`}
            template={selected === "new" ? null : current}
            onSaved={(id) => setSelected(id)}
          />
        )}
      </div>
      <AssignmentsAdmin />
    </div>
  );
}

function TemplateEditor({ template, onSaved }: { template: Template | null; onSaved: (id: string) => void }) {
  const qc = useQueryClient();
  const [name, setName] = useState(template?.name ?? "");
  const [description, setDescription] = useState(template?.description ?? "");
  const [active, setActive] = useState(template?.active ?? true);
  const [def, setDef] = useState<Definition>(template?.definition ?? BLANK);
  const [chosen, setChosen] = useState<string | null>(def.start);
  const [debounced, setDebounced] = useState(def);
  useEffect(() => {
    const h = setTimeout(() => setDebounced(def), 400);
    return () => clearTimeout(h);
  }, [def]);
  const check = useQuery({
    queryKey: ["processCheck", debounced],
    queryFn: () => unwrap(api.POST("/api/v1/process-templates/check", { body: debounced })),
  });
  const versions = useQuery({
    queryKey: ["processTemplates", template?.id, "versions"],
    queryFn: () => unwrap(api.GET("/api/v1/process-templates/{id}/versions", { params: { path: { id: template!.id } } })),
    enabled: !!template,
  });
  const save = useMutation({
    mutationFn: () => {
      const body = { name: name.trim(), description: description.trim() || null, active, definition: def };
      return template
        ? unwrap(api.PUT("/api/v1/process-templates/{id}", { params: { path: { id: template.id } }, body }))
        : unwrap(api.POST("/api/v1/process-templates", { body }));
    },
    onSuccess: (saved) => {
      void qc.invalidateQueries({ queryKey: ["processTemplates"] });
      onSaved(saved.id);
    },
  });
  const update = (id: string, change: Partial<Step>) =>
    setDef((d) => ({ ...d, nodes: d.nodes.map((n) => (n.id === id ? { ...n, ...change } : n)) }));
  const add = (kind: Kind) => {
    const base = kind.toLowerCase();
    let i = 1;
    while (def.nodes.some((n) => n.id === `${base}${i}`)) i++;
    const id = `${base}${i}`;
    const below = def.nodes.reduce((m, n) => Math.max(m, (n.y ?? 0) + H + 40), 40);
    const step: Step = mk({
      id,
      kind,
      label: kindLabel(kind),
      x: 40,
      y: below,
      ...(kind === "TASK" ? { assignees: ["starter"], choices: ["DONE"], outcomes: {} } : {}),
      ...(kind === "VOTE" ? { assignees: ["role:MANAGER"], choices: ["APPROVE", "REJECT"], rule: "MAJORITY" as const, outcomes: {} } : {}),
      ...(kind === "ACTION" ? { action: "SET_STATE" as const, state: "RELEASED" } : {}),
      ...(kind === "CONDITION" ? { expression: "object.state == 'IN_WORK'", outcomes: {} } : {}),
      ...(kind === "TIMER" ? { duration: "P1D" } : {}),
      ...(kind === "END" ? { outcome: "DONE" } : {}),
    });
    setDef((d) => ({ ...d, nodes: [...d.nodes, step] }));
    setChosen(id);
  };
  const remove = (id: string) => {
    setDef((d) => ({
      start: d.start === id ? (d.nodes.find((n) => n.id !== id)?.id ?? "") : d.start,
      nodes: d.nodes
        .filter((n) => n.id !== id)
        .map((n) => ({
          ...n,
          next: n.next === id ? null : n.next,
          join: n.join === id ? null : n.join,
          branches: (n.branches ?? []).filter((b) => b !== id),
          outcomes: Object.fromEntries(Object.entries(n.outcomes ?? {}).filter(([, v]) => v !== id)),
        })),
    }));
    setChosen(null);
  };
  const errors = check.data?.errors ?? [];
  const step = def.nodes.find((n) => n.id === chosen) ?? null;
  return (
    <Panel
      title={template ? t("{name} · version {version}", { name: template.name, version: template.version }) : t("New process template")}
      actions={
        <button className="btn small primary" disabled={!name.trim() || errors.length > 0 || save.isPending} onClick={() => save.mutate()}>
          <Save size={13} aria-hidden /> {template ? t("Save as version {n}", { n: template.version + 1 }) : t("Save")}
        </button>
      }
    >
      <div className="attr-form cols-2" style={{ marginBottom: 12 }}>
        <div className="field">
          <label htmlFor="pt-name">{t("Name")}</label>
          <input id="pt-name" value={name} onChange={(e) => setName(e.target.value)} />
        </div>
        <div className="field">
          <span className="label-spacer" aria-hidden />
          <label className="check">
            <input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} /> {t("In use: people can start it")}
          </label>
        </div>
        <div className="field wide">
          <label htmlFor="pt-description">{t("Description")}</label>
          <input id="pt-description" value={description} onChange={(e) => setDescription(e.target.value)} />
        </div>
      </div>
      <div className="palette" role="toolbar" aria-label={t("Add a step")}>
        <span className="muted small">{t("Add a step:")}</span>
        {KINDS().map((k) => (
          <button key={k.kind} className={`btn small kind-${k.kind.toLowerCase()}`} title={k.hint} onClick={() => add(k.kind)}>
            {k.label}
          </button>
        ))}
      </div>
      <div className="process-canvas-wrap">
        <Canvas def={def} chosen={chosen} onChoose={setChosen} onMove={(id, x, y) => update(id, { x, y })} />
        <aside className="inspector" aria-label={t("Step")}>
          {step ? (
            <Inspector
              key={step.id}
              step={step}
              def={def}
              onChange={(c) => update(step.id, c)}
              onStart={() => setDef((d) => ({ ...d, start: step.id }))}
              onRemove={() => remove(step.id)}
            />
          ) : (
            <div className="muted">{t("Choose a step to change it.")}</div>
          )}
        </aside>
      </div>
      <div className={errors.length ? "banner error" : "banner info"} role="status" style={{ marginTop: 12 }}>
        {errors.length ? (
          <>
            <CircleAlert size={14} aria-hidden />{" "}
            {tn("{n} problem to fix before saving:", "{n} problems to fix before saving:", errors.length)}
            <ul style={{ margin: "4px 0 0", paddingLeft: 18 }}>
              {errors.map((e) => (
                <li key={e}>{e}</li>
              ))}
            </ul>
          </>
        ) : (
          <>
            <CircleCheck size={14} aria-hidden /> {t("The process can run.")}
          </>
        )}
      </div>
      <ErrorBanner error={save.error} />
      {versions.data && versions.data.length > 0 && (
        <div className="muted small" style={{ marginTop: 8 }}>
          {t("Versions:")} {versions.data.map((v) => `v${v.version} ${formatDate(v.createdAt)}`).join(" · ")}.{" "}
          {t("Running processes keep the version they started with.")}
        </div>
      )}
    </Panel>
  );
}

/** The steps as boxes, their ways out as arrows; drag a box to move it. */
function Canvas({
  def,
  chosen,
  onChoose,
  onMove,
}: {
  def: Definition;
  chosen: string | null;
  onChoose: (id: string) => void;
  onMove: (id: string, x: number, y: number) => void;
}) {
  const svg = useRef<SVGSVGElement>(null);
  const [drag, setDrag] = useState<{ id: string; dx: number; dy: number } | null>(null);
  const byId = useMemo(() => new Map(def.nodes.map((n) => [n.id, n])), [def.nodes]);
  const width = Math.max(600, ...def.nodes.map((n) => (n.x ?? 0) + W + 20));
  const height = Math.max(260, ...def.nodes.map((n) => (n.y ?? 0) + H + 90));
  // The drawing scales to the panel: pointer positions go back to drawing units.
  const point = (e: ReactPointerEvent) => {
    const r = svg.current!.getBoundingClientRect();
    const k = width / r.width;
    return { x: (e.clientX - r.left) * k, y: (e.clientY - r.top) * k };
  };
  return (
    <div className="process-canvas">
      <svg
        ref={svg}
        width="100%"
        style={{ minWidth: width * 0.75 }}
        viewBox={`0 0 ${width} ${height}`}
        role="img"
        aria-label={t("Process diagram")}
        onPointerMove={(e) => {
          if (!drag) return;
          const p = point(e);
          onMove(drag.id, Math.max(0, Math.round((p.x - drag.dx) / 10) * 10), Math.max(0, Math.round((p.y - drag.dy) / 10) * 10));
        }}
        onPointerUp={() => setDrag(null)}
        onPointerLeave={() => setDrag(null)}
      >
        <defs>
          <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M 0 0 L 10 5 L 0 10 z" className="edge-head" />
          </marker>
        </defs>
        {def.nodes.flatMap((n) =>
          exits(n).map(([label, to], i) => {
            const target = byId.get(to);
            if (!target) return null;
            const x1 = (n.x ?? 0) + W;
            const y1 = (n.y ?? 0) + H / 2;
            const x2 = target.x ?? 0;
            const y2 = (target.y ?? 0) + H / 2;
            const back = x2 <= x1;
            const d = back
              ? `M ${x1} ${y1} C ${x1 + 60} ${y1 + 90}, ${x2 - 60} ${y2 + 90}, ${x2} ${y2}`
              : `M ${x1} ${y1} C ${x1 + 40} ${y1}, ${x2 - 40} ${y2}, ${x2} ${y2}`;
            const mx = (x1 + x2) / 2;
            const my = back ? Math.max(y1, y2) + 70 : (y1 + y2) / 2 - 6;
            return (
              <g key={`${n.id}-${i}`} className="edge">
                <path d={d} markerEnd="url(#arrow)" />
                {label && (
                  <text x={mx} y={my} textAnchor="middle" className="edge-label">
                    {label}
                  </text>
                )}
              </g>
            );
          }),
        )}
        {def.nodes.map((n) => (
          <g
            key={n.id}
            transform={`translate(${n.x ?? 0}, ${n.y ?? 0})`}
            className={`node kind-${n.kind.toLowerCase()}${n.id === chosen ? " chosen" : ""}${n.id === def.start ? " start" : ""}`}
            role="button"
            tabIndex={0}
            aria-label={`${kindLabel(n.kind)}: ${n.label ?? n.id}`}
            onKeyDown={(e) => e.key === "Enter" && onChoose(n.id)}
            onPointerDown={(e) => {
              const p = point(e);
              onChoose(n.id);
              setDrag({ id: n.id, dx: p.x - (n.x ?? 0), dy: p.y - (n.y ?? 0) });
            }}
          >
            <rect width={W} height={H} rx={n.kind === "END" ? 27 : n.kind === "CONDITION" ? 4 : 8} />
            <text x={10} y={20} className="node-kind">
              {n.id === def.start ? `▶ ${kindLabel(n.kind)}` : kindLabel(n.kind)}
            </text>
            <text x={10} y={40} className="node-label">
              {(n.label ?? n.id).slice(0, 20)}
            </text>
          </g>
        ))}
      </svg>
    </div>
  );
}

const csv = (l: string[] | undefined) => (l ?? []).join(", ");
const list = (s: string) =>
  s
    .split(",")
    .map((x) => x.trim())
    .filter(Boolean);

function Inspector({
  step,
  def,
  onChange,
  onStart,
  onRemove,
}: {
  step: Step;
  def: Definition;
  onChange: (c: Partial<Step>) => void;
  onStart: () => void;
  onRemove: () => void;
}) {
  const { data: lifecycles } = useLifecycles();
  const states = [
    ...new Map((lifecycles ?? []).flatMap((l) => l.states.map((s) => [s.key, pickLabel(s.labels, s.key)] as const))).entries(),
  ];
  const others = def.nodes.filter((n) => n.id !== step.id);
  const target = (value: string | null | undefined, set: (v: string | null) => void, label: string, id: string) => (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <select id={id} value={value ?? ""} onChange={(e) => set(e.target.value || null)}>
        <option value="">—</option>
        {others.map((n) => (
          <option key={n.id} value={n.id}>
            {n.label ?? n.id} ({kindLabel(n.kind)})
          </option>
        ))}
      </select>
    </div>
  );
  const isTask = step.kind === "TASK" || step.kind === "VOTE";
  return (
    <div className="inspector-form">
      <div className="row" style={{ justifyContent: "space-between" }}>
        <strong>{kindLabel(step.kind)}</strong>
        <span className="muted small mono">{step.id}</span>
      </div>
      <div className="field">
        <label htmlFor="st-label">{t("Label")}</label>
        <input id="st-label" value={step.label ?? ""} onChange={(e) => onChange({ label: e.target.value })} />
      </div>
      {isTask && (
        <>
          <div className="field">
            <label htmlFor="st-who">{t("Who")}</label>
            <input id="st-who" defaultValue={csv(step.assignees)} onBlur={(e) => onChange({ assignees: list(e.target.value) })} />
            <span className="hint">{t("role:MANAGER, user:alice, starter, owner")}</span>
          </div>
          <div className="field">
            <label htmlFor="st-choices">{t("Answers")}</label>
            <input
              id="st-choices"
              defaultValue={csv(step.choices)}
              onBlur={(e) => {
                const choices = list(e.target.value).map((c) => c.toUpperCase().replace(/\s+/g, "_"));
                onChange({
                  choices,
                  outcomes: Object.fromEntries(Object.entries(step.outcomes ?? {}).filter(([k]) => choices.includes(k))),
                });
              }}
            />
          </div>
          {step.kind === "VOTE" && (
            <div className="field">
              <label htmlFor="st-rule">{t("The votes decide")}</label>
              <select id="st-rule" value={step.rule ?? "ALL"} onChange={(e) => onChange({ rule: e.target.value as Step["rule"] })}>
                <option value="ALL">{t("All must approve")}</option>
                <option value="ANY">{t("One approval is enough")}</option>
                <option value="MAJORITY">{t("By majority")}</option>
              </select>
            </div>
          )}
          <div className="field">
            <label htmlFor="st-due">{t("Due in")}</label>
            <input id="st-due" value={step.dueIn ?? ""} placeholder="P3D" onChange={(e) => onChange({ dueIn: e.target.value || null })} />
          </div>
          {step.kind === "TASK" && (
            <div className="field">
              <label htmlFor="st-escalate">{t("When overdue, also give it to")}</label>
              <input id="st-escalate" defaultValue={csv(step.escalateTo)} onBlur={(e) => onChange({ escalateTo: list(e.target.value) })} />
            </div>
          )}
          <div className="field">
            <label htmlFor="st-instr">{t("Instructions")}</label>
            <textarea
              id="st-instr"
              rows={2}
              value={step.instructions ?? ""}
              onChange={(e) => onChange({ instructions: e.target.value || null })}
            />
          </div>
          {(step.choices ?? []).map((c) =>
            target(
              step.outcomes?.[c],
              (v) =>
                onChange({
                  outcomes: Object.fromEntries(Object.entries({ ...step.outcomes, [c]: v }).filter(([, x]) => x)) as Record<string, string>,
                }),
              t("After {answer}", { answer: c }),
              `st-out-${c}`,
            ),
          )}
        </>
      )}
      {step.kind === "ACTION" && (
        <>
          <div className="field">
            <label htmlFor="st-action">{t("Does")}</label>
            <select id="st-action" value={step.action ?? ""} onChange={(e) => onChange({ action: e.target.value as Step["action"] })}>
              <option value="SET_STATE">{t("Set the state")}</option>
              <option value="SET_ATTRIBUTE">{t("Set a value")}</option>
              <option value="NOTIFY">{t("Notify")}</option>
            </select>
          </div>
          {step.action === "SET_STATE" && (
            <div className="field">
              <label htmlFor="st-state">{t("State")}</label>
              <select id="st-state" value={step.state ?? ""} onChange={(e) => onChange({ state: e.target.value })}>
                {states.map(([k, l]) => (
                  <option key={k} value={k}>
                    {l}
                  </option>
                ))}
              </select>
            </div>
          )}
          {step.action === "SET_ATTRIBUTE" && (
            <>
              <div className="field">
                <label htmlFor="st-attr">{t("Attribute")}</label>
                <input id="st-attr" value={step.attribute ?? ""} onChange={(e) => onChange({ attribute: e.target.value })} />
              </div>
              <div className="field">
                <label htmlFor="st-value">{t("Value")}</label>
                <input id="st-value" value={String(step.value ?? "")} onChange={(e) => onChange({ value: e.target.value })} />
              </div>
            </>
          )}
          {step.action === "NOTIFY" && (
            <>
              <div className="field">
                <label htmlFor="st-who">{t("Who")}</label>
                <input id="st-who" defaultValue={csv(step.assignees)} onBlur={(e) => onChange({ assignees: list(e.target.value) })} />
              </div>
              <div className="field">
                <label htmlFor="st-msg">{t("Message")}</label>
                <input id="st-msg" value={step.message ?? ""} onChange={(e) => onChange({ message: e.target.value })} />
              </div>
            </>
          )}
          {target(step.next, (v) => onChange({ next: v }), t("Then"), "st-next")}
        </>
      )}
      {step.kind === "CONDITION" && (
        <>
          <div className="field">
            <label htmlFor="st-expr">{t("Condition (CEL)")}</label>
            <input id="st-expr" className="mono" value={step.expression ?? ""} onChange={(e) => onChange({ expression: e.target.value })} />
          </div>
          {target(step.outcomes?.["true"], (v) => onChange({ outcomes: { ...step.outcomes, true: v ?? "" } }), t("When true"), "st-true")}
          {target(
            step.outcomes?.["false"],
            (v) => onChange({ outcomes: { ...step.outcomes, false: v ?? "" } }),
            t("When false"),
            "st-false",
          )}
        </>
      )}
      {step.kind === "PARALLEL" && (
        <>
          <fieldset className="check-list">
            <legend>{t("Branches")}</legend>
            {others.map((n) => (
              <label key={n.id} className="check">
                <input
                  type="checkbox"
                  checked={(step.branches ?? []).includes(n.id)}
                  onChange={(e) =>
                    onChange({
                      branches: e.target.checked ? [...(step.branches ?? []), n.id] : (step.branches ?? []).filter((b) => b !== n.id),
                    })
                  }
                />{" "}
                {n.label ?? n.id}
              </label>
            ))}
          </fieldset>
          {target(step.join, (v) => onChange({ join: v }), t("Meet at"), "st-join")}
        </>
      )}
      {step.kind === "JOIN" && target(step.next, (v) => onChange({ next: v }), t("Then"), "st-next")}
      {step.kind === "TIMER" && (
        <>
          <div className="field">
            <label htmlFor="st-dur">{t("Wait")}</label>
            <input
              id="st-dur"
              value={step.duration ?? ""}
              placeholder="PT4H, P2D"
              onChange={(e) => onChange({ duration: e.target.value })}
            />
          </div>
          {target(step.next, (v) => onChange({ next: v }), t("Then"), "st-next")}
        </>
      )}
      {step.kind === "END" && (
        <div className="field">
          <label htmlFor="st-outcome">{t("Outcome")}</label>
          <input id="st-outcome" value={step.outcome ?? ""} onChange={(e) => onChange({ outcome: e.target.value.toUpperCase() })} />
        </div>
      )}
      <div className="row" style={{ gap: 6, marginTop: 8 }}>
        {def.start !== step.id && (
          <button className="btn small" onClick={onStart}>
            {t("Start here")}
          </button>
        )}
        <button className="btn small ghost danger" onClick={onRemove}>
          <Trash2 size={13} aria-hidden /> {t("Remove")}
        </button>
      </div>
    </div>
  );
}
