import { Pencil, Plus, Trash2 } from "lucide-react";
import { useState } from "react";
import { useTypes } from "../api/queries";
import { Dialog, ErrorBanner, Loading, Panel, PrincipalName, formatDate, typeLabel } from "../components/common";
import { ExpressionField } from "../components/ExpressionField";
import {
  useDefineOperationRule,
  useDeleteOperationRule,
  useOperationRules,
  useUpdateOperationRule,
  type OperationRule,
} from "./adminQueries";

/** The operations rules can refuse, as people say them. */
export const OPERATIONS: [string, string][] = [
  ["object.create", "Create"],
  ["object.check-out", "Check out"],
  ["object.check-in", "Check in"],
  ["object.revise", "Revise"],
  ["object.set-state", "Set state"],
  ["object.rename", "Rename"],
  ["object.change-lifecycle", "Change lifecycle"],
];
export const OPERATION_LABEL: Record<string, string> = Object.fromEntries(OPERATIONS);

/**
 * Operation rules (roadmap 2.5): refuse an operation when a condition holds. The site's own are
 * edited here; extensions' are listed with their source and change with the extension.
 */
export function RulesAdmin() {
  const { data: rules, isLoading, error } = useOperationRules();
  const remove = useDeleteOperationRule();
  const [editing, setEditing] = useState<OperationRule | "new" | null>(null);

  if (isLoading) return <Loading />;
  return (
    <Panel
      title="Operation rules"
      note="Checked before the operation runs; every rule that refuses is shown. Administrator overrides of the lifecycle skip them."
      flush
      actions={
        <button className="btn small primary" onClick={() => setEditing("new")}>
          <Plus size={13} aria-hidden />
          New rule
        </button>
      }
    >
      <ErrorBanner error={error ?? remove.error} />
      {!rules?.length ? (
        <p className="empty">
          No rules yet. A rule refuses an operation when its condition holds, e.g. check-in when{" "}
          <code>object.attributes.description == null</code>.
        </p>
      ) : (
        <table className="grid" aria-label="Operation rules">
          <thead>
            <tr>
              <th>Rule</th>
              <th>Refuses</th>
              <th>Refuse when</th>
              <th>Message</th>
              <th>From</th>
              <th aria-label="Actions" />
            </tr>
          </thead>
          <tbody>
            {rules.map((r) => (
              <tr key={r.id} className={r.active ? undefined : "inactive"}>
                <td>
                  <strong>{r.name}</strong>
                  <div className="muted small">
                    {r.types.length ? r.types.join(", ") : "All types"}
                    {!r.active && " · inactive"}
                  </div>
                </td>
                <td>
                  <div className="row wrap" style={{ gap: 4 }}>
                    {r.operations.map((o) => (
                      <span key={o} className="tag">
                        {OPERATION_LABEL[o] ?? o}
                      </span>
                    ))}
                  </div>
                </td>
                <td>
                  <code className="wrap-code">{r.condition}</code>
                </td>
                <td>{r.message}</td>
                <td className="nowrap">
                  {r.source === "site" ? "This site" : <code>{r.source}</code>}
                  {r.updatedAt && (
                    <div className="muted small">
                      {formatDate(r.updatedAt)}
                      {r.updatedBy && (
                        <>
                          {" · "}
                          <PrincipalName id={r.updatedBy} />
                        </>
                      )}
                    </div>
                  )}
                </td>
                <td className="nowrap">
                  {r.source === "site" ? (
                    <>
                      <button className="icon-btn" aria-label={`Edit ${r.name}`} onClick={() => setEditing(r)}>
                        <Pencil size={13} />
                      </button>
                      <button
                        className="icon-btn"
                        aria-label={`Delete ${r.name}`}
                        disabled={remove.isPending}
                        onClick={() => window.confirm(`Delete the rule “${r.name}”?`) && remove.mutate(r.id)}
                      >
                        <Trash2 size={13} />
                      </button>
                    </>
                  ) : (
                    <span className="muted small" title="Changed by upgrading or disabling the extension">
                      Extension
                    </span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {editing && <RuleDialog rule={editing === "new" ? null : editing} onClose={() => setEditing(null)} />}
    </Panel>
  );
}

function RuleDialog({ rule, onClose }: { rule: OperationRule | null; onClose: () => void }) {
  const { data: types } = useTypes();
  const define = useDefineOperationRule();
  const update = useUpdateOperationRule();
  const [name, setName] = useState(rule?.name ?? "");
  const [operations, setOperations] = useState<string[]>(rule?.operations ?? ["object.check-in"]);
  const [typeNames, setTypeNames] = useState<string[]>(rule?.types ?? []);
  const [condition, setCondition] = useState(rule?.condition ?? "");
  const [message, setMessage] = useState(rule?.message ?? "");
  const [active, setActive] = useState(rule?.active ?? true);
  const toggle = (list: string[], set: (l: string[]) => void, v: string) =>
    set(list.includes(v) ? list.filter((x) => x !== v) : [...list, v]);
  const saving = define.isPending || update.isPending;

  const save = () => {
    const body = { name, operations, types: typeNames, condition, message, active };
    if (rule) update.mutate({ id: rule.id, body }, { onSuccess: onClose });
    else define.mutate(body, { onSuccess: onClose });
  };

  return (
    <Dialog
      open
      wide
      title={rule ? `Edit ${rule.name}` : "New operation rule"}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button
            className="btn primary"
            disabled={saving || !name.trim() || !condition.trim() || !message.trim() || operations.length === 0}
            onClick={save}
          >
            Save
          </button>
        </>
      }
    >
      <div className="attr-form">
        <ErrorBanner error={define.error ?? update.error} />
        <div className="field wide">
          <label htmlFor="op-rule-name">Name</label>
          <input id="op-rule-name" value={name} onChange={(e) => setName(e.target.value)} autoFocus />
        </div>
        <fieldset className="check-list wide">
          <legend>Refuses</legend>
          {OPERATIONS.map(([key, label]) => (
            <label key={key} className="check">
              <input type="checkbox" checked={operations.includes(key)} onChange={() => toggle(operations, setOperations, key)} />
              {label}
            </label>
          ))}
        </fieldset>
        <ExpressionField
          id="op-rule-condition"
          label="Refuse when"
          value={condition}
          onChange={setCondition}
          rows={3}
          placeholder="event.to == 'RELEASED' && event.comment == null"
          hint="The operation is refused when this is true."
        />
        <div className="field wide">
          <label htmlFor="op-rule-message">Message</label>
          <input
            id="op-rule-message"
            value={message}
            onChange={(e) => setMessage(e.target.value)}
            placeholder="What the user sees, and what to do"
          />
        </div>
        <fieldset className="check-list wide">
          <legend>Types (subtypes included; none ticked = all types)</legend>
          {types?.map((t) => (
            <label key={t.id} className="check">
              <input type="checkbox" checked={typeNames.includes(t.name)} onChange={() => toggle(typeNames, setTypeNames, t.name)} />
              {typeLabel(t)}
            </label>
          ))}
        </fieldset>
        <label className="check wide">
          <input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} />
          Active
        </label>
      </div>
    </Dialog>
  );
}
