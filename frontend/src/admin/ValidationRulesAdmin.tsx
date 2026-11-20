import { Pencil, Plus, Trash2 } from "lucide-react";
import { useState } from "react";
import { useTypeAttributes } from "../api/queries";
import { pickLabel } from "../components/AttributeForm";
import { Dialog, ErrorBanner, Loading, Panel, PrincipalName, formatDate } from "../components/common";
import { ExpressionField } from "../components/ExpressionField";
import {
  useDefineValidationRule,
  useDeleteValidationRule,
  useUpdateValidationRule,
  useValidationRules,
  type TypeDefinition,
  type ValidationRule,
} from "./adminQueries";

/**
 * A type's validation rules: conditions its objects (and its subtypes') must meet to be created
 * or checked in, with the message users see. Inherited rules are listed first, read-only here.
 */
export function ValidationRulesPanel({ type, typeName }: { type: TypeDefinition; typeName: (id: string) => string }) {
  const { data: rules, isLoading, error } = useValidationRules(type.id);
  const remove = useDeleteValidationRule();
  const [editing, setEditing] = useState<ValidationRule | "new" | null>(null);

  if (isLoading) return <Loading />;
  return (
    <Panel
      title="Validation rules"
      note="Checked when an object is created or checked in; every failing rule's message is shown"
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
          No rules yet. A rule is a condition, such as{" "}
          <code>object.attributes.source != 'buy' || object.attributes.description != null</code>, and the message users see when it's not
          met.
        </p>
      ) : (
        <table className="grid" aria-label="Validation rules">
          <thead>
            <tr>
              <th>Rule</th>
              <th>Valid when</th>
              <th>Message</th>
              <th>Shown at</th>
              <th>Changed</th>
              <th aria-label="Actions" />
            </tr>
          </thead>
          <tbody>
            {rules.map((r) => (
              <tr key={r.id} className={r.active ? undefined : "inactive"}>
                <td>
                  <strong>{r.name}</strong>
                  {r.inherited && <div className="muted small">From {typeName(r.typeId)}</div>}
                  {!r.active && <div className="muted small">Inactive</div>}
                </td>
                <td>
                  <code className="wrap-code">{r.condition}</code>
                </td>
                <td>{r.message}</td>
                <td className="nowrap">{r.attribute ?? <span className="muted">Whole object</span>}</td>
                <td className="nowrap muted">
                  {formatDate(r.updatedAt)} · <PrincipalName id={r.updatedBy} />
                </td>
                <td className="nowrap">
                  {!r.inherited && (
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
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {editing && <RuleDialog type={type} rule={editing === "new" ? null : editing} onClose={() => setEditing(null)} />}
    </Panel>
  );
}

function RuleDialog({ type, rule, onClose }: { type: TypeDefinition; rule: ValidationRule | null; onClose: () => void }) {
  const define = useDefineValidationRule(type.id);
  const update = useUpdateValidationRule();
  const { data: attributes } = useTypeAttributes(type.id);
  const [name, setName] = useState(rule?.name ?? "");
  const [condition, setCondition] = useState(rule?.condition ?? "");
  const [message, setMessage] = useState(rule?.message ?? "");
  const [attribute, setAttribute] = useState(rule?.attribute ?? "");
  const [active, setActive] = useState(rule?.active ?? true);
  const saving = define.isPending || update.isPending;

  const save = () => {
    const body = { name, condition, message, attribute: attribute || null, active, order: rule?.order ?? 0 };
    if (rule) update.mutate({ id: rule.id, body }, { onSuccess: onClose });
    else define.mutate(body, { onSuccess: onClose });
  };

  return (
    <Dialog
      open
      title={rule ? `Edit ${rule.name}` : `New rule for ${type.displayName}`}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button className="btn primary" disabled={saving || !name.trim() || !condition.trim() || !message.trim()} onClick={save}>
            Save
          </button>
        </>
      }
    >
      <div className="attr-form">
        <ErrorBanner error={define.error ?? update.error} />
        <div className="field">
          <label htmlFor="rule-name">Name</label>
          <input id="rule-name" value={name} onChange={(e) => setName(e.target.value)} autoFocus />
        </div>
        <div className="field">
          <label htmlFor="rule-attribute">Show the message at</label>
          <select id="rule-attribute" value={attribute} onChange={(e) => setAttribute(e.target.value)}>
            <option value="">The whole object</option>
            {attributes?.map((a) => (
              <option key={a.name} value={a.name}>
                {pickLabel(a.labels, a.name)}
              </option>
            ))}
          </select>
        </div>
        <ExpressionField
          id="rule-condition"
          label="Valid when"
          value={condition}
          onChange={setCondition}
          rows={3}
          placeholder="object.attributes.source != 'buy' || object.attributes.description != null"
          hint="The object may be saved when this is true."
        />
        <div className="field wide">
          <label htmlFor="rule-message">Message when it isn't</label>
          <input id="rule-message" value={message} onChange={(e) => setMessage(e.target.value)} placeholder="Describe parts you buy" />
        </div>
        <label className="check wide">
          <input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} />
          Active
        </label>
      </div>
    </Dialog>
  );
}
