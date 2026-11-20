import { Box, FileText, Pencil, Plus, X } from "lucide-react";
import { useMemo, useState, type FormEvent } from "react";
import { useSearchParams } from "react-router";
import { useLifecycles, useTypes, type AttributeDefinition } from "../api/queries";
import { pickLabel } from "../components/AttributeForm";
import { Dialog, ErrorBanner, Loading, Panel, TYPE_ICONS, TypeIcon } from "../components/common";
import {
  useAllAttributes,
  useAssignLifecycle,
  useGlobalAttributes,
  useGlobalUse,
  useUpdateType,
  useCreateType,
  useNumberingSchemes,
  useTypeLifecycle,
  useTypeRules,
  useUpdateRules,
  type RevisionScheme,
  type TypeDefinition,
} from "./adminQueries";
import { AttributeDialog, constraintSummary, dataTypeLabel } from "./AttributeDialog";
import { LayoutEditor } from "./LayoutEditor";

type View = "properties" | "attributes" | "layouts" | "rules";

/** Types as a tree, root types first, children under their parent. */
function useTypeTree() {
  const { data: types } = useTypes();
  return useMemo(() => {
    const list = types ?? [];
    const children = new Map<string | null, TypeDefinition[]>();
    for (const t of list) children.set(t.parentId ?? null, [...(children.get(t.parentId ?? null) ?? []), t]);
    for (const v of children.values()) v.sort((a, b) => a.displayName.localeCompare(b.displayName));
    const rows: { type: TypeDefinition; depth: number }[] = [];
    const walk = (parent: string | null, depth: number) => {
      for (const t of children.get(parent) ?? []) {
        rows.push({ type: t, depth });
        walk(t.id, depth + 1);
      }
    };
    walk(null, 0);
    return { rows, byId: new Map(list.map((t) => [t.id, t])), loaded: !!types };
  }, [types]);
}

export function TypesAdmin() {
  const [params, setParams] = useSearchParams();
  const tree = useTypeTree();
  const [creating, setCreating] = useState(false);
  const selectedId = params.get("type") ?? tree.rows[0]?.type.id;
  const selected = selectedId ? tree.byId.get(selectedId) : undefined;
  const view = (params.get("view") as View | null) ?? "attributes";
  const go = (next: Record<string, string>) => setParams({ tab: "types", type: selectedId ?? "", view, ...next });

  if (!tree.loaded) return <Loading />;

  return (
    <div className="admin-split">
      <Panel
        title="Types"
        flush
        actions={
          selected && (
            <button className="btn small" onClick={() => setCreating(true)}>
              <Plus size={13} aria-hidden />
              New subtype
            </button>
          )
        }
      >
        <ul className="type-tree" aria-label="Types">
          {tree.rows.map(({ type, depth }) => (
            <li key={type.id}>
              <button
                className={type.id === selectedId ? "type-row active" : "type-row"}
                style={{ paddingLeft: 10 + depth * 16 }}
                aria-current={type.id === selectedId ? "true" : undefined}
                onClick={() => go({ type: type.id })}
              >
                <TypeIcon typeId={type.id} size={14} />
                <span className={type.instantiable ? undefined : "abstract"} title={type.instantiable ? undefined : "Abstract: groups subtypes, can't be created"}>
                  {type.displayName}
                </span>
              </button>
            </li>
          ))}
        </ul>
      </Panel>
      {selected ? (
        <div className="stack" key={selected.id}>
          <div className="admin-head">
            <h2>{selected.displayName}</h2>
            <span className="muted">
              <code>{selected.name}</code> · {selected.parentId ? `subtype of ${tree.byId.get(selected.parentId)?.displayName}` : "out-of-the-box base type"}
            </span>
          </div>
          <nav className="subtabs" aria-label="Type settings">
            {(
              [
                ["properties", "Properties"],
                ["attributes", "Attributes"],
                ["layouts", "Layouts"],
                ["rules", "Rules and lifecycle"],
              ] as [View, string][]
            ).map(([key, label]) => (
              <button key={key} className={view === key ? "active" : undefined} aria-pressed={view === key} onClick={() => go({ view: key })}>
                {label}
              </button>
            ))}
          </nav>
          {view === "properties" && <PropertiesPanel key={selected.id} type={selected} />}
          {view === "attributes" && <AttributesPanel type={selected} typeName={(id) => tree.byId.get(id)?.displayName ?? "…"} />}
          {view === "layouts" && <LayoutEditor type={selected} typeName={(id) => tree.byId.get(id)?.displayName ?? "…"} />}
          {view === "rules" && <RulesPanel type={selected} typeName={(id) => tree.byId.get(id)?.displayName ?? "…"} />}
        </div>
      ) : (
        <p className="empty">Select a type.</p>
      )}
      {selected && (
        <NewSubtypeDialog
          parent={selected}
          open={creating}
          onClose={() => setCreating(false)}
          onCreated={(id) => {
            setCreating(false);
            go({ type: id, view: "attributes" });
          }}
        />
      )}
    </div>
  );
}

function NewSubtypeDialog({
  parent,
  open,
  onClose,
  onCreated,
}: {
  parent: TypeDefinition;
  open: boolean;
  onClose: () => void;
  onCreated: (id: string) => void;
}) {
  const create = useCreateType();
  const [displayName, setDisplayName] = useState("");
  const [name, setName] = useState("");
  // The internal name follows the display name until the admin edits it.
  const [nameEdited, setNameEdited] = useState(false);
  const derived = displayName.replace(/[^A-Za-z0-9]+/g, " ").trim().split(" ").filter(Boolean).map((w) => w[0]!.toUpperCase() + w.slice(1)).join("");
  const effectiveName = nameEdited ? name : derived;

  const submit = (e: FormEvent) => {
    e.preventDefault();
    create.mutate(
      { parentId: parent.id, name: effectiveName, displayName: displayName.trim() },
      {
        onSuccess: (t) => {
          setDisplayName("");
          setName("");
          setNameEdited(false);
          onCreated(t.id);
        },
      },
    );
  };

  return (
    <Dialog
      open={open}
      title={`New subtype of ${parent.displayName}`}
      onClose={onClose}
      footer={
        <>
          <button className="btn" type="button" onClick={onClose}>
            Cancel
          </button>
          <button className="btn primary" type="submit" form="new-type-form" disabled={create.isPending || !displayName.trim() || !effectiveName}>
            Create
          </button>
        </>
      }
    >
      <form id="new-type-form" onSubmit={submit} noValidate style={{ display: "grid", gap: 12 }}>
        <ErrorBanner error={create.error} />
        <p className="muted" style={{ margin: 0 }}>
          It inherits every attribute, layout, rule and lifecycle of {parent.displayName}; you can then add and override.
        </p>
        <div className="field">
          <label htmlFor="type-display">Display name</label>
          <input id="type-display" value={displayName} onChange={(e) => setDisplayName(e.target.value)} autoFocus required />
        </div>
        <div className="field">
          <label htmlFor="type-name">Internal name</label>
          <input
            id="type-name"
            value={effectiveName}
            onChange={(e) => {
              setNameEdited(true);
              setName(e.target.value);
            }}
          />
          <span className="hint">Used by integrations and the API. Can't be changed later.</span>
        </div>
      </form>
    </Dialog>
  );
}

// ---------- Properties ----------

function PropertiesPanel({ type }: { type: TypeDefinition }) {
  const update = useUpdateType(type.id);
  const [displayName, setDisplayName] = useState(type.displayName);
  const [description, setDescription] = useState(type.description ?? "");
  const [icon, setIcon] = useState(type.icon ?? "");
  const [instantiable, setInstantiable] = useState(type.instantiable);
  const [labels, setLabels] = useState<[string, string][]>(Object.entries(type.labels ?? {}));
  const problems = (update.error as { problem?: { details?: { errors?: unknown } } } | null)?.problem?.details?.errors;

  return (
    <form
      className="panel"
      aria-label="Type properties"
      noValidate
      onSubmit={(e) => {
        e.preventDefault();
        update.mutate({
          displayName: displayName.trim(),
          description: description.trim() || null,
          icon: icon || null,
          instantiable,
          labels: Object.fromEntries(labels.filter(([lang, label]) => lang.trim() && label.trim()).map(([l, v]) => [l.trim(), v.trim()])),
        });
      }}
    >
      <div className="panel-head">
        <h2>Properties</h2>
        <span className="note">
          Internal name <code>{type.name}</code> and base class {type.baseClass.toLowerCase()} are fixed
        </span>
      </div>
      <div className="panel-body" style={{ display: "grid", gap: 12 }}>
        {Array.isArray(problems) ? (
          <div className="banner error" role="alert">
            {(problems as string[]).join(" · ")}
          </div>
        ) : (
          <ErrorBanner error={update.error} />
        )}
        <div className="attr-form cols-2">
          <div className="field">
            <label htmlFor="type-prop-name">Display name</label>
            <input id="type-prop-name" value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="type-prop-desc">Description</label>
            <input id="type-prop-desc" value={description} onChange={(e) => setDescription(e.target.value)} />
          </div>
        </div>
        <fieldset className="attr-group">
          <legend className="section-label">Icon</legend>
          <div className="icon-picker" role="radiogroup" aria-label="Icon">
            <label className={icon === "" ? "on" : undefined} title="The base class's icon">
              <input type="radio" name="type-icon" checked={icon === ""} onChange={() => setIcon("")} />
              {type.baseClass === "DOCUMENT" ? <FileText size={16} aria-hidden /> : <Box size={16} aria-hidden />}
              <span className="sr-only">Default</span>
            </label>
            {Object.entries(TYPE_ICONS).map(([key, Icon]) => (
              <label key={key} className={icon === key ? "on" : undefined} title={key}>
                <input type="radio" name="type-icon" checked={icon === key} onChange={() => setIcon(key)} />
                <Icon size={16} aria-hidden />
                <span className="sr-only">{key}</span>
              </label>
            ))}
          </div>
        </fieldset>
        <fieldset className="attr-group">
          <legend className="section-label">Translations of the name</legend>
          {labels.map(([lang, label], i) => (
            <div className="row" key={i}>
              <input aria-label={`Language ${i + 1}`} placeholder="fr" value={lang} style={{ width: 90, flex: "none" }} onChange={(e) => setLabels(labels.map((x, j) => (j === i ? [e.target.value, x[1]] : x)))} />
              <input aria-label={`Translation ${i + 1}`} placeholder="Pièce" value={label} onChange={(e) => setLabels(labels.map((x, j) => (j === i ? [x[0], e.target.value] : x)))} />
              <button type="button" className="btn small ghost icon" aria-label={`Remove translation ${i + 1}`} onClick={() => setLabels(labels.filter((_, j) => j !== i))}>
                <X size={13} aria-hidden />
              </button>
            </div>
          ))}
          <div>
            <button type="button" className="btn small" onClick={() => setLabels([...labels, ["", ""]])}>
              <Plus size={13} aria-hidden />
              Add a translation
            </button>
          </div>
        </fieldset>
        <label className="check">
          <input type="checkbox" checked={!instantiable} onChange={(e) => setInstantiable(!e.target.checked)} />
          Abstract: only groups subtypes; users can't create objects of this type itself
        </label>
      </div>
      <div className="panel-foot">
        <button className="btn primary" type="submit" disabled={update.isPending || !displayName.trim()}>
          Save
        </button>
        {update.isSuccess && <span className="muted">Saved.</span>}
      </div>
    </form>
  );
}

// ---------- Attributes ----------

function AttributesPanel({ type, typeName }: { type: TypeDefinition; typeName: (id: string) => string }) {
  const { data: attributes, isLoading, error } = useAllAttributes(type.id);
  const { data: globals } = useGlobalAttributes();
  const use = useGlobalUse();
  const [editing, setEditing] = useState<AttributeDefinition | "new" | null>(null);
  const present = new Set((attributes ?? []).map((a) => a.name));
  const addable = (globals ?? []).filter((g) => !present.has(g.attribute.name));

  return (
    <Panel
      title="Attributes"
      note="Inherited attributes come first; edit them on the type that defines them to change every subtype"
      flush
      actions={
        <>
          {addable.length > 0 && (
            <select
              aria-label="Use a global attribute"
              value=""
              style={{ width: 200 }}
              onChange={(e) => e.target.value && use.mutate({ typeId: type.id, attributeId: e.target.value, use: true })}
            >
              <option value="">Use a global attribute…</option>
              {addable.map((g) => (
                <option key={g.attribute.id} value={g.attribute.id}>
                  {pickLabel(g.attribute.labels, g.attribute.name)}
                </option>
              ))}
            </select>
          )}
          <button className="btn small primary" onClick={() => setEditing("new")}>
            <Plus size={13} aria-hidden />
            Add attribute
          </button>
        </>
      }
    >
      <ErrorBanner error={error ?? use.error} />
      {isLoading ? (
        <Loading />
      ) : (
        <div className="grid-wrap">
          <table className="grid" aria-label="Attributes">
            <thead>
              <tr>
                <th>Label</th>
                <th>Name</th>
                <th>Data type</th>
                <th>Constraints</th>
                <th>Defined on</th>
                <th>Status</th>
                <th aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {(attributes ?? []).map((a) => (
                <tr key={a.id} className={a.active ? undefined : "inactive"}>
                  <td className="nowrap">{pickLabel(a.labels, a.name)}</td>
                  <td className="nowrap">
                    <code>{a.name}</code>
                  </td>
                  <td className="nowrap">{dataTypeLabel(a)}</td>
                  <td className="muted">{constraintSummary(a) || "—"}</td>
                  <td className="nowrap">
                    {a.inherited ? typeName(a.typeId) : <span className="tag accent">This type</span>}
                    {a.global && (
                      <span className="tag" title="A global attribute: defined once, shared by several types" style={{ marginLeft: 4 }}>
                        Global
                      </span>
                    )}
                  </td>
                  <td className="nowrap">{a.active ? "Active" : <span className="muted">Inactive</span>}</td>
                  <td className="nowrap" style={{ width: 70 }}>
                    <button className="btn small ghost icon" aria-label={`Edit ${pickLabel(a.labels, a.name)}`} title="Edit" onClick={() => setEditing(a)}>
                      <Pencil size={13} aria-hidden />
                    </button>
                    {a.global && !a.inherited && (
                      <button
                        className="btn small ghost icon"
                        aria-label={`Stop using ${pickLabel(a.labels, a.name)}`}
                        title="Stop using this global attribute here (stored values are kept)"
                        onClick={() => use.mutate({ typeId: type.id, attributeId: a.id, use: false })}
                      >
                        <X size={13} aria-hidden />
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {editing && (
        <AttributeDialog
          type={type}
          attribute={editing === "new" ? undefined : editing}
          definedOn={editing === "new" ? type.displayName : typeName(editing.typeId)}
          onClose={() => setEditing(null)}
        />
      )}
    </Panel>
  );
}

// ---------- Rules ----------

const REVISION_SCHEMES: { key: RevisionScheme; label: string }[] = [
  { key: "ALPHABETIC_ASME", label: "Letters, ASME (A, B … Y, AA; no I, O, Q, S, X, Z)" },
  { key: "ALPHABETIC", label: "Letters (A, B … Z, AA)" },
  { key: "NUMERIC_FROM_1", label: "Numbers from 1" },
  { key: "NUMERIC_FROM_0", label: "Numbers from 0" },
];

function RulesPanel({ type, typeName }: { type: TypeDefinition; typeName: (id: string) => string }) {
  const { data: rules, error } = useTypeRules(type.id);
  const { data: schemes } = useNumberingSchemes();
  const { data: lifecycles } = useLifecycles();
  const { data: lifecycle } = useTypeLifecycle(type.id);
  const update = useUpdateRules(type.id);
  const assign = useAssignLifecycle(type.id);

  if (!rules) return error ? <ErrorBanner error={error} /> : <Loading />;
  // A rule is this type's own when it isn't inherited from another type.
  const own = (key: string) => rules.inheritedFrom[key] === type.id;
  const origin = (key: string) => {
    const from = rules.inheritedFrom[key];
    if (!from) return "System default";
    return from === type.id ? "Set on this type" : `Inherited from ${typeName(from)}`;
  };
  const current = {
    numberingSchemeId: own("numberingScheme") ? rules.numberingSchemeId : null,
    revisionScheme: own("revisionScheme") ? rules.revisionScheme : null,
    allowManualNumber: own("allowManualNumber") ? rules.allowManualNumber : null,
  };
  const save = (patch: Partial<typeof current>) => update.mutate({ ...current, ...patch });
  const schemeName = (id: string | null | undefined) =>
    id ? (schemes?.find((s) => s.id === id)?.name ?? "…") : "System sequence (10 digits)";

  return (
    <div className="cols-even">
      <Panel title="Numbering and revisions" note="Apply to objects created from now on">
        <ErrorBanner error={update.error} />
        <div className="rule-grid">
          <label htmlFor="rule-numbering">Numbering</label>
          <div>
            <select
              id="rule-numbering"
              value={own("numberingScheme") ? (rules.numberingSchemeId ?? "") : "inherit"}
              onChange={(e) => save({ numberingSchemeId: e.target.value === "inherit" ? null : e.target.value })}
            >
              <option value="inherit">Inherit ({schemeName(own("numberingScheme") ? null : rules.numberingSchemeId)})</option>
              {schemes?.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name} — next {s.example}
                </option>
              ))}
            </select>
            <span className="hint">{origin("numberingScheme")}</span>
          </div>
          <label htmlFor="rule-revision">Revision scheme</label>
          <div>
            <select
              id="rule-revision"
              value={current.revisionScheme ?? "inherit"}
              onChange={(e) => save({ revisionScheme: e.target.value === "inherit" ? null : (e.target.value as RevisionScheme) })}
            >
              <option value="inherit">Inherit ({REVISION_SCHEMES.find((r) => r.key === rules.revisionScheme)?.label})</option>
              {REVISION_SCHEMES.map((r) => (
                <option key={r.key} value={r.key}>
                  {r.label}
                </option>
              ))}
            </select>
            <span className="hint">{origin("revisionScheme")}</span>
          </div>
          <label htmlFor="rule-manual">Manual numbers</label>
          <div>
            <select
              id="rule-manual"
              value={current.allowManualNumber == null ? "inherit" : String(current.allowManualNumber)}
              onChange={(e) => save({ allowManualNumber: e.target.value === "inherit" ? null : e.target.value === "true" })}
            >
              <option value="inherit">Inherit ({rules.allowManualNumber ? "allowed" : "not allowed"})</option>
              <option value="true">Allowed: users may type their own number</option>
              <option value="false">Not allowed: always the next generated number</option>
            </select>
            <span className="hint">{origin("allowManualNumber")}</span>
          </div>
        </div>
      </Panel>
      <Panel title="Lifecycle" note="Objects keep the lifecycle they were created with">
        <ErrorBanner error={assign.error} />
        <div className="rule-grid">
          <label htmlFor="rule-lifecycle">Lifecycle</label>
          <div>
            <select id="rule-lifecycle" value={lifecycle?.id ?? ""} onChange={(e) => assign.mutate(e.target.value)}>
              {lifecycles?.map((l) => (
                <option key={l.id} value={l.id}>
                  {pickLabel(l.labels, l.name)}
                </option>
              ))}
            </select>
            <span className="hint">Subtypes without their own assignment use this one.</span>
          </div>
        </div>
        {lifecycle && (
          <p className="muted" style={{ margin: "10px 0 0" }}>
            States: {lifecycle.states.map((s) => pickLabel(s.labels, s.key)).join(" → ")}
          </p>
        )}
      </Panel>
    </div>
  );
}
