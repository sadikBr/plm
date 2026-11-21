import { Copy, Pencil, Plus, Save, Trash2 } from "lucide-react";
import { useState, type FormEvent } from "react";
import { useLifecycles } from "../api/queries";
import { pickLabel } from "../components/AttributeForm";
import { Dialog, ErrorBanner, Loading, Panel } from "../components/common";
import { ExpressionField } from "../components/ExpressionField";
import { t } from "../i18n/i18n";
import { useCreateLifecycle, useLifecycleVersions, useUpdateLifecycle, type Lifecycle } from "./adminQueries";

interface StateDraft {
  key: string;
  label: string;
  editable: boolean;
  revisable: boolean;
  /** Comma-separated attribute names, as typed. */
  required?: string;
  revisionScheme?: string | null;
  /** Gate: condition to enter, and the message when it isn't met. */
  entryCondition?: string | null;
  entryMessage?: string | null;
}

const SERIES = (): { key: string; label: string }[] => [
  { key: "ALPHABETIC_ASME", label: t("Letters (ASME)") },
  { key: "ALPHABETIC", label: t("Letters") },
  { key: "NUMERIC_FROM_1", label: t("Numbers from 1") },
  { key: "NUMERIC_FROM_0", label: t("Numbers from 0") },
];

interface Diagram {
  states: StateDraft[];
  transitions: { from: string; to: string }[];
  initial: string;
}

const NODE_W = 118;
const NODE_H = 36;
const GAP = 46;

/**
 * States left to right in template order; transitions to a later state arc above the row, back to
 * an earlier state arc below it. Editable states are tinted, revisable ones get a marker.
 */
export function StateDiagram({ states, transitions, initial }: Diagram) {
  const index = new Map(states.map((s, i) => [s.key, i]));
  const x = (i: number) => 24 + i * (NODE_W + GAP);
  const bendOf = (span: number) => 26 + span * 16;
  // Room above for forward arcs, below for backward ones. A cubic curve peaks at 3/4 of its control offset.
  const spans = transitions
    .map((t) => ({ a: index.get(t.from), b: index.get(t.to) }))
    .filter((e): e is { a: number; b: number } => e.a !== undefined && e.b !== undefined && e.a !== e.b);
  const above = Math.max(0, ...spans.filter((e) => e.b - e.a > 1).map((e) => bendOf(e.b - e.a) * 0.75));
  const below = Math.max(0, ...spans.filter((e) => e.b < e.a).map((e) => bendOf(e.a - e.b) * 0.75));
  const rowY = 14 + above;
  const width = Math.max(320, x(states.length) - GAP + 24);
  const height = rowY + NODE_H + below + 14;

  return (
    <svg
      className="state-diagram"
      viewBox={`0 0 ${width} ${height}`}
      style={{ maxWidth: width }}
      role="img"
      aria-label={t("State diagram")}
    >
      <defs>
        <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
          <path d="M0,0 L10,5 L0,10 z" className="arrow-head" />
        </marker>
      </defs>
      {transitions.map((t) => {
        const a = index.get(t.from);
        const b = index.get(t.to);
        if (a === undefined || b === undefined || a === b) return null;
        const forward = b > a;
        const span = Math.abs(b - a);
        const x1 = x(a) + NODE_W / 2 + (forward ? 12 : -12);
        const x2 = x(b) + NODE_W / 2 + (forward ? -12 : 12);
        const y = forward ? rowY : rowY + NODE_H;
        // Adjacent forward steps are straight; everything else bends further the further it goes.
        if (forward && span === 1) {
          return (
            <line
              key={`${t.from}-${t.to}`}
              x1={x(a) + NODE_W}
              y1={rowY + NODE_H / 2}
              x2={x(b) - 2}
              y2={rowY + NODE_H / 2}
              className="edge"
              markerEnd="url(#arrow)"
            />
          );
        }
        const bend = bendOf(span);
        const cy = forward ? y - bend : y + bend;
        return (
          <path
            key={`${t.from}-${t.to}`}
            d={`M${x1},${y} C${x1},${cy} ${x2},${cy} ${x2},${y}`}
            className={forward ? "edge" : "edge back"}
            markerEnd="url(#arrow)"
          />
        );
      })}
      {states.map((s, i) => (
        <g key={s.key || i}>
          {s.key === initial && <circle cx={x(i) - 10} cy={rowY + NODE_H / 2} r={4} className="initial" />}
          <rect x={x(i)} y={rowY} width={NODE_W} height={NODE_H} rx={5} className={s.editable ? "node editable" : "node"} />
          <text x={x(i) + NODE_W / 2} y={rowY + NODE_H / 2 + 4} textAnchor="middle" className="node-label">
            {s.label || s.key || "?"}
          </text>
          {s.revisable && (
            <text x={x(i) + NODE_W - 6} y={rowY + 11} textAnchor="end" className="node-flag">
              {t("rev")}
            </text>
          )}
        </g>
      ))}
    </svg>
  );
}

export function LifecyclesAdmin() {
  const { data: lifecycles, isLoading } = useLifecycles();
  const [selected, setSelected] = useState<string | null>(null);
  const [draftFrom, setDraftFrom] = useState<Lifecycle | "blank" | null>(null);
  const [editing, setEditing] = useState(false);
  const current = lifecycles?.find((l) => l.id === selected) ?? lifecycles?.[0];

  if (isLoading) return <Loading />;
  return (
    <div className="admin-split">
      <Panel
        title={t("Lifecycles")}
        flush
        actions={
          <button className="btn small" onClick={() => setDraftFrom("blank")}>
            <Plus size={13} aria-hidden />
            {t("New lifecycle")}
          </button>
        }
      >
        <ul className="type-tree" aria-label={t("Lifecycles")}>
          {lifecycles?.map((l) => (
            <li key={l.id}>
              <button
                className={!draftFrom && current?.id === l.id ? "type-row active" : "type-row"}
                onClick={() => {
                  setDraftFrom(null);
                  setSelected(l.id);
                }}
              >
                {pickLabel(l.labels, l.name)}
                <span className="muted" style={{ marginLeft: "auto" }}>
                  v{l.version}
                  {l.system && ` · ${t("out of the box")}`}
                </span>
              </button>
            </li>
          ))}
        </ul>
      </Panel>
      {draftFrom ? (
        <LifecycleEditor
          key={draftFrom === "blank" ? "blank" : `${draftFrom.id}-${editing}`}
          from={draftFrom === "blank" ? undefined : draftFrom}
          editing={editing}
          onDone={(id) => {
            setDraftFrom(null);
            setEditing(false);
            if (id) setSelected(id);
          }}
        />
      ) : current ? (
        <Panel
          title={pickLabel(current.labels, current.name)}
          note={t("Version {version}. Editing saves a new version; objects keep theirs until an administrator moves them.", {
            version: current.version,
          })}
          actions={
            <>
              <button
                className="btn small primary"
                onClick={() => {
                  setEditing(true);
                  setDraftFrom(current);
                }}
              >
                <Pencil size={13} aria-hidden />
                {t("Edit")}
              </button>
              <button className="btn small" onClick={() => setDraftFrom(current)}>
                <Copy size={13} aria-hidden />
                {t("Copy as new")}
              </button>
            </>
          }
        >
          <StateDiagram
            states={current.states.map((s) => ({
              key: s.key,
              label: pickLabel(s.labels, s.key),
              editable: !!s.editable,
              revisable: !!s.revisable,
            }))}
            transitions={current.transitions}
            initial={current.initialState}
          />
          <table className="grid" aria-label={t("States")} style={{ marginTop: 12 }}>
            <thead>
              <tr>
                <th>{t("State")}</th>
                <th>{t("Key")}</th>
                <th>{t("Can be edited")}</th>
                <th>{t("Can be revised from")}</th>
                <th>{t("Required to enter")}</th>
                <th>{t("Revision series")}</th>
                <th>{t("Entry condition")}</th>
                <th>{t("Can move to")}</th>
              </tr>
            </thead>
            <tbody>
              {current.states.map((s) => (
                <tr key={s.key}>
                  <td className="nowrap">
                    {pickLabel(s.labels, s.key)}
                    {s.key === current.initialState && (
                      <span className="tag" style={{ marginLeft: 6 }}>
                        {t("Initial")}
                      </span>
                    )}
                  </td>
                  <td>
                    <code>{s.key}</code>
                  </td>
                  <td>{s.editable ? t("Yes") : t("No")}</td>
                  <td>{s.revisable ? t("Yes") : t("No")}</td>
                  <td className="muted">{s.requiredAttributes?.join(", ") || "—"}</td>
                  <td className="muted">{SERIES().find((x) => x.key === s.revisionScheme)?.label ?? "—"}</td>
                  <td className="muted">
                    {s.entryCondition ? (
                      <code className="wrap-code" title={s.entryMessage ?? undefined}>
                        {s.entryCondition}
                      </code>
                    ) : (
                      "—"
                    )}
                  </td>
                  <td className="muted">
                    {current.transitions
                      .filter((t) => t.from === s.key)
                      .map((t) => pickLabel(current.states.find((x) => x.key === t.to)?.labels, t.to))
                      .join(", ") || "—"}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <Versions id={current.id} />
        </Panel>
      ) : null}
    </div>
  );
}

const BLANK = (): StateDraft[] => [
  { key: "IN_WORK", label: t("In Work"), editable: true, revisable: false },
  { key: "RELEASED", label: t("Released"), editable: false, revisable: true },
];

/** Creates a lifecycle (new, or a copy of [from]), or with [editing] saves a new version of [from]. */
function LifecycleEditor({ from, editing = false, onDone }: { from?: Lifecycle; editing?: boolean; onDone: (createdId?: string) => void }) {
  const create = useCreateLifecycle();
  const update = useUpdateLifecycle();
  const mutation = editing ? update : create;
  const [name, setName] = useState(from ? (editing ? from.name : `${from.name}Copy`) : "");
  const [label, setLabel] = useState(
    from ? (editing ? pickLabel(from.labels, from.name) : t("{name} (copy)", { name: pickLabel(from.labels, from.name) })) : "",
  );
  const [states, setStates] = useState<StateDraft[]>(
    from?.states.map((s) => ({
      key: s.key,
      label: pickLabel(s.labels, s.key),
      editable: !!s.editable,
      revisable: !!s.revisable,
      required: (s.requiredAttributes ?? []).join(", "),
      revisionScheme: s.revisionScheme ?? null,
      entryCondition: s.entryCondition ?? null,
      entryMessage: s.entryMessage ?? null,
    })) ?? BLANK(),
  );
  const [transitions, setTransitions] = useState(from?.transitions ?? [{ from: "IN_WORK", to: "RELEASED" }]);
  const [initial, setInitial] = useState(from?.initialState ?? "IN_WORK");

  const [gateOf, setGateOf] = useState<number | null>(null);
  const keys = states.map((s) => s.key);
  const has = (a: string, b: string) => transitions.some((t) => t.from === a && t.to === b);
  const toggle = (a: string, b: string) =>
    setTransitions(has(a, b) ? transitions.filter((t) => !(t.from === a && t.to === b)) : [...transitions, { from: a, to: b }]);
  const setState = (i: number, patch: Partial<StateDraft>) => {
    const old = states[i]!.key;
    setStates(states.map((s, j) => (j === i ? { ...s, ...patch } : s)));
    // Renaming a key carries its transitions and the initial state along.
    if (patch.key !== undefined && patch.key !== old) {
      setTransitions(transitions.map((t) => ({ from: t.from === old ? patch.key! : t.from, to: t.to === old ? patch.key! : t.to })));
      if (initial === old) setInitial(patch.key);
    }
  };
  const removeState = (i: number) => {
    const key = states[i]!.key;
    setStates(states.filter((_, j) => j !== i));
    setTransitions(transitions.filter((t) => t.from !== key && t.to !== key));
  };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const body = {
      name: name.trim(),
      labels: { en: label.trim() },
      initialState: initial,
      states: states.map((s) => ({
        key: s.key.trim(),
        labels: { en: s.label.trim() || s.key },
        editable: !!s.editable,
        revisable: !!s.revisable,
        requiredAttributes: (s.required ?? "")
          .split(",")
          .map((x) => x.trim())
          .filter(Boolean),
        revisionScheme: (s.revisable ? (s.revisionScheme ?? null) : null) as Lifecycle["states"][number]["revisionScheme"],
        entryCondition: s.entryCondition?.trim() || null,
        entryMessage: s.entryCondition?.trim() ? s.entryMessage?.trim() || null : null,
      })),
      transitions: transitions.filter((t) => keys.includes(t.from) && keys.includes(t.to)),
    };
    const done = { onSuccess: (lc: Lifecycle) => onDone(lc.id) };
    if (editing && from) update.mutate({ id: from.id, body }, done);
    else create.mutate(body, done);
  };
  const problems = (mutation.error as { problem?: { details?: { errors?: unknown } } } | null)?.problem?.details?.errors;

  return (
    <form className="panel" aria-label={editing ? t("Edit lifecycle") : t("New lifecycle")} onSubmit={submit} noValidate>
      <div className="panel-head">
        <h2>
          {editing && from
            ? t("{name}: version {version}", { name: pickLabel(from.labels, from.name), version: from.version + 1 })
            : from
              ? t("Copy of {name}", { name: pickLabel(from.labels, from.name) })
              : t("New lifecycle")}
        </h2>
        <span className="note">
          {editing
            ? t("Saving creates a new version. Types move to it; existing objects keep their version until moved.")
            : t("Assign it to types under Types and attributes → Numbering and lifecycle")}
        </span>
      </div>
      <div className="panel-body" style={{ display: "grid", gap: 12 }}>
        {Array.isArray(problems) ? (
          <div className="banner error" role="alert">
            <ul style={{ margin: 0, paddingLeft: 16 }}>
              {(problems as string[]).map((p) => (
                <li key={p}>{p}</li>
              ))}
            </ul>
          </div>
        ) : (
          <ErrorBanner error={mutation.error} />
        )}
        <div className="attr-form cols-2">
          <div className="field">
            <label htmlFor="lc-label">{t("Label")}</label>
            <input id="lc-label" value={label} onChange={(e) => setLabel(e.target.value)} required />
          </div>
          <div className="field">
            <label htmlFor="lc-name">{t("Name")}</label>
            <input
              id="lc-name"
              value={name}
              disabled={editing}
              onChange={(e) => setName(e.target.value)}
              placeholder={t("e.g. {example}", { example: "FastTrack" })}
            />
          </div>
        </div>

        <StateDiagram states={states} transitions={transitions} initial={initial} />

        <div className="section-label">{t("States")}</div>
        <table className="grid" aria-label={t("States")}>
          <thead>
            <tr>
              <th>{t("Key")}</th>
              <th>{t("Label")}</th>
              <th>{t("Initial")}</th>
              <th title={t("Objects in this state can be checked out and edited")}>{t("Editable")}</th>
              <th title={t("A new revision can be started from this state")}>{t("Revisable")}</th>
              <th title={t("Attributes that must have a value before an object may enter this state")}>{t("Required to enter")}</th>
              <th title={t("Revisions started from this state continue in this series")}>{t("Revision series")}</th>
              <th title={t("A condition an object must meet to enter this state")}>{t("Entry condition")}</th>
              <th aria-label={t("Remove")} />
            </tr>
          </thead>
          <tbody>
            {states.map((s, i) => (
              <tr key={i}>
                <td>
                  <input
                    aria-label={t("Key of state {n}", { n: i + 1 })}
                    value={s.key}
                    onChange={(e) => setState(i, { key: e.target.value.toUpperCase().replace(/[^A-Z0-9_]/g, "_") })}
                  />
                </td>
                <td>
                  <input
                    aria-label={t("Label of state {n}", { n: i + 1 })}
                    value={s.label}
                    onChange={(e) => setState(i, { label: e.target.value })}
                  />
                </td>
                <td style={{ width: 60 }}>
                  <input
                    type="radio"
                    name="initial"
                    aria-label={t("{state} is the initial state", { state: s.label || s.key })}
                    checked={initial === s.key}
                    onChange={() => setInitial(s.key)}
                    style={{ width: "auto" }}
                  />
                </td>
                <td style={{ width: 80 }}>
                  <input
                    type="checkbox"
                    aria-label={t("{state} is editable", { state: s.label || s.key })}
                    checked={s.editable}
                    onChange={(e) => setState(i, { editable: e.target.checked })}
                    style={{ width: "auto" }}
                  />
                </td>
                <td style={{ width: 80 }}>
                  <input
                    type="checkbox"
                    aria-label={t("{state} is revisable", { state: s.label || s.key })}
                    checked={s.revisable}
                    onChange={(e) => setState(i, { revisable: e.target.checked })}
                    style={{ width: "auto" }}
                  />
                </td>
                <td>
                  <input
                    aria-label={t("Attributes required to enter {state}", { state: s.label || s.key })}
                    placeholder={t("e.g. {example}", { example: "material, mass" })}
                    value={s.required ?? ""}
                    onChange={(e) => setState(i, { required: e.target.value })}
                  />
                </td>
                <td style={{ width: 150 }}>
                  <select
                    aria-label={t("Revision series from {state}", { state: s.label || s.key })}
                    value={s.revisionScheme ?? ""}
                    disabled={!s.revisable}
                    onChange={(e) => setState(i, { revisionScheme: e.target.value || null })}
                  >
                    <option value="">{t("Unchanged")}</option>
                    {SERIES().map((x) => (
                      <option key={x.key} value={x.key}>
                        {x.label}
                      </option>
                    ))}
                  </select>
                </td>
                <td className="nowrap">
                  <button
                    type="button"
                    className="btn small"
                    aria-label={t("Entry condition of {state}", { state: s.label || s.key })}
                    onClick={() => setGateOf(i)}
                  >
                    {s.entryCondition ? t("Edit…") : t("Add…")}
                  </button>
                </td>
                <td style={{ width: 40 }}>
                  <button
                    type="button"
                    className="btn small ghost icon"
                    aria-label={t("Remove state {state}", { state: s.label || s.key })}
                    disabled={states.length <= 1}
                    onClick={() => removeState(i)}
                  >
                    <Trash2 size={12} aria-hidden />
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <div>
          <button
            type="button"
            className="btn small"
            onClick={() => setStates([...states, { key: `STATE_${states.length + 1}`, label: "", editable: false, revisable: false }])}
          >
            <Plus size={13} aria-hidden />
            {t("Add state")}
          </button>
        </div>

        <div className="section-label">{t("Allowed transitions (from row → to column)")}</div>
        <div className="grid-wrap">
          <table className="grid matrix" aria-label={t("Transitions")}>
            <thead>
              <tr>
                <th>{t("From \\ To")}</th>
                {states.map((s) => (
                  <th key={s.key}>{s.label || s.key}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {states.map((a) => (
                <tr key={a.key}>
                  <th scope="row">{a.label || a.key}</th>
                  {states.map((b) => (
                    <td key={b.key}>
                      {a.key !== b.key && (
                        <input
                          type="checkbox"
                          aria-label={t("{from} to {to}", { from: a.label || a.key, to: b.label || b.key })}
                          checked={has(a.key, b.key)}
                          onChange={() => toggle(a.key, b.key)}
                          style={{ width: "auto" }}
                        />
                      )}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      <div className="panel-foot">
        <button className="btn primary" type="submit" disabled={mutation.isPending || !label.trim() || !name.trim()}>
          <Save size={14} aria-hidden />
          {editing ? t("Save new version") : t("Create lifecycle")}
        </button>
        <button type="button" className="btn" onClick={() => onDone()}>
          {t("Cancel")}
        </button>
      </div>
      {gateOf != null && states[gateOf] && (
        <GateDialog
          state={states[gateOf]!}
          onSave={(entryCondition, entryMessage) => {
            setState(gateOf, { entryCondition, entryMessage });
            setGateOf(null);
          }}
          onClose={() => setGateOf(null)}
        />
      )}
    </form>
  );
}

function Versions({ id }: { id: string }) {
  const { data } = useLifecycleVersions(id);
  if (!data || data.length < 2) return null;
  return (
    <p className="muted" style={{ margin: "12px 0 0" }}>
      {t("Versions: {versions}. Objects created earlier keep the version they started with.", {
        versions: data.map((v) => (v.latest ? t("v{version} (current)", { version: v.version }) : `v${v.version}`)).join(" · "),
      })}
    </p>
  );
}

/** A state's gate: the condition to enter it and the message when it isn't met. */
function GateDialog({
  state,
  onSave,
  onClose,
}: {
  state: StateDraft;
  onSave: (condition: string | null, message: string | null) => void;
  onClose: () => void;
}) {
  const [condition, setCondition] = useState(state.entryCondition ?? "");
  const [message, setMessage] = useState(state.entryMessage ?? "");
  return (
    <Dialog
      open
      title={t("Entry condition of {state}", { state: state.label || state.key })}
      onClose={onClose}
      footer={
        <>
          <button type="button" className="btn" onClick={() => onSave(null, null)} disabled={!state.entryCondition}>
            {t("Remove")}
          </button>
          <span className="grow" />
          <button type="button" className="btn" onClick={onClose}>
            {t("Cancel")}
          </button>
          <button
            type="button"
            className="btn primary"
            disabled={!condition.trim() || !message.trim()}
            onClick={() => onSave(condition.trim(), message.trim())}
          >
            {t("Done")}
          </button>
        </>
      }
    >
      <div className="attr-form">
        <p className="muted wide" style={{ margin: 0 }}>
          {t(
            "Checked on the object as it is when someone moves it to {state}. Site administrators can override it. Saved with the lifecycle.",
            {
              state: state.label || state.key,
            },
          )}
        </p>
        <ExpressionField
          id="gate-condition"
          label={t("Enter only when")}
          value={condition}
          onChange={setCondition}
          rows={3}
          placeholder="object.attributes.source == 'make' || object.attributes.description != null"
        />
        <div className="field wide">
          <label htmlFor="gate-message">{t("Message when it isn't met")}</label>
          <input
            id="gate-message"
            value={message}
            onChange={(e) => setMessage(e.target.value)}
            placeholder={t("Bought parts need a description before release")}
          />
        </div>
      </div>
    </Dialog>
  );
}
