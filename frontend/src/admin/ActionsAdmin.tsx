import { ArrowDown, ArrowUp, Pencil, RotateCcw } from "lucide-react";
import { useState } from "react";
import {
  useActionRegistry,
  useConfigureAction,
  useLifecycles,
  useTypes,
  type ActionConfiguration,
  type RegisteredAction,
} from "../api/queries";
import { Dialog, ErrorBanner, Loading, Panel, PrincipalName, formatDate } from "../components/common";
import { ACTION_ICONS } from "../pages/ObjectActions";

type Placement = RegisteredAction["effective"]["placement"];

const PLACEMENTS: [Placement, string][] = [
  ["OBJECT_TOOLBAR", "Object toolbar"],
  ["OBJECT_MORE", "Object More menu"],
];
const SHORT: Record<Placement, string> = { OBJECT_TOOLBAR: "Toolbar", OBJECT_MORE: "More menu" };

/** The current configuration of an action, ready to send back with one field changed. */
function configOf(a: RegisteredAction): ActionConfiguration {
  return { ...(a.configuration ?? {}), hidden: a.hidden };
}

/**
 * The action registry: every action on objects, where it shows and in what order, who sees it on
 * which types and states. Built-in actions are configured like any extension's.
 */
export function ActionsAdmin() {
  const { data: registry, isLoading, error } = useActionRegistry();
  const { configure, reset } = useConfigureAction();
  const [editing, setEditing] = useState<RegisteredAction | null>(null);

  if (isLoading) return <Loading />;
  const busy = configure.isPending || reset.isPending;
  const put = (a: RegisteredAction, change: ActionConfiguration) => configure.mutate({ id: a.id, body: { ...configOf(a), ...change } });

  /** Moves [a] one place within its placement, renumbering that placement 10, 20, 30… */
  const move = (list: RegisteredAction[], index: number, by: -1 | 1) => {
    const next = [...list];
    const [item] = next.splice(index, 1);
    next.splice(index + by, 0, item!);
    next.forEach((x, i) => {
      if (x.effective.order !== (i + 1) * 10) put(x, { order: (i + 1) * 10 });
    });
  };

  return (
    <div style={{ display: "grid", gap: 12 }}>
      <ErrorBanner error={error ?? configure.error ?? reset.error} />
      {PLACEMENTS.map(([placement, title]) => {
        const list = (registry ?? []).filter((a) => a.effective.placement === placement);
        return (
          <Panel key={placement} title={title} note={`${list.length} action${list.length === 1 ? "" : "s"}, in display order`} flush>
            <table className="grid" aria-label={title}>
              <thead>
                <tr>
                  <th aria-label="Order" />
                  <th>Action</th>
                  <th>Shows in</th>
                  <th>Applies to</th>
                  <th>Source</th>
                  <th>Changed</th>
                  <th aria-label="Actions" />
                </tr>
              </thead>
              <tbody>
                {list.map((a, i) => {
                  const Icon = (a.effective.icon && ACTION_ICONS[a.effective.icon]) || null;
                  return (
                    <tr key={a.id} className={a.hidden ? "inactive" : undefined}>
                      <td className="nowrap">
                        <button
                          className="icon-btn"
                          aria-label={`Move ${a.effective.label} up`}
                          disabled={busy || i === 0}
                          onClick={() => move(list, i, -1)}
                        >
                          <ArrowUp size={13} />
                        </button>
                        <button
                          className="icon-btn"
                          aria-label={`Move ${a.effective.label} down`}
                          disabled={busy || i === list.length - 1}
                          onClick={() => move(list, i, 1)}
                        >
                          <ArrowDown size={13} />
                        </button>
                      </td>
                      <td>
                        <div className="row" style={{ gap: 6 }}>
                          {Icon && <Icon size={14} className="icon-muted" aria-hidden />}
                          <strong>{a.effective.label}</strong>
                        </div>
                        <div className="muted small">
                          <code>{a.id}</code> · {a.declared.description}
                        </div>
                      </td>
                      <td>
                        <select
                          aria-label={`Where ${a.effective.label} shows`}
                          value={a.hidden ? "HIDDEN" : a.effective.placement}
                          disabled={busy}
                          onChange={(e) =>
                            e.target.value === "HIDDEN"
                              ? put(a, { hidden: true })
                              : put(a, { hidden: false, placement: e.target.value as Placement })
                          }
                        >
                          {PLACEMENTS.map(([p]) => (
                            <option key={p} value={p}>
                              {SHORT[p]}
                            </option>
                          ))}
                          <option value="HIDDEN">Hidden</option>
                        </select>
                      </td>
                      <td className="muted">{scope(a)}</td>
                      <td className="nowrap">{a.source === "platform" ? "Built in" : a.source}</td>
                      <td className="nowrap muted">
                        {a.configuredAt ? (
                          <>
                            {formatDate(a.configuredAt)} · <PrincipalName id={a.configuredBy!} />
                          </>
                        ) : (
                          "Default"
                        )}
                      </td>
                      <td className="nowrap">
                        <button
                          className="icon-btn"
                          aria-label={`Edit ${a.effective.label}`}
                          title="Label, types and states"
                          onClick={() => setEditing(a)}
                        >
                          <Pencil size={13} />
                        </button>
                        <button
                          className="icon-btn"
                          aria-label={`Reset ${a.effective.label}`}
                          title="Back to the defaults"
                          disabled={busy || !a.configuration}
                          onClick={() => reset.mutate(a.id)}
                        >
                          <RotateCcw size={13} />
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </Panel>
        );
      })}
      {editing && <ActionDialog action={editing} onClose={() => setEditing(null)} />}
    </div>
  );
}

function scope(a: RegisteredAction) {
  const types = a.effective.types.length ? a.effective.types.join(", ") : "All types";
  const states = a.effective.states.length ? a.effective.states.join(", ") : "all states";
  return `${types} · ${states}${a.effective.requires ? ` · needs ${a.effective.requires.toLowerCase()}` : ""}`;
}

/** Label, and which types and states the action applies to. */
function ActionDialog({ action, onClose }: { action: RegisteredAction; onClose: () => void }) {
  const { data: types } = useTypes();
  const { data: lifecycles } = useLifecycles();
  const { configure } = useConfigureAction();
  const [label, setLabel] = useState(action.configuration?.label ?? "");
  const [typeNames, setTypeNames] = useState<string[]>(action.effective.types);
  const [states, setStates] = useState<string[]>(action.effective.states);
  // Every state any lifecycle has, once, with its first label.
  const stateKeys = [...new Map((lifecycles ?? []).flatMap((l) => l.states.map((s) => [s.key, s.labels.en ?? s.key] as const))).entries()];
  const toggle = (list: string[], set: (l: string[]) => void, value: string) =>
    set(list.includes(value) ? list.filter((v) => v !== value) : [...list, value]);
  const same = (a: string[], b: string[]) => a.length === b.length && a.every((x) => b.includes(x));

  return (
    <Dialog
      open
      title={`Edit ${action.effective.label}`}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button
            className="btn primary"
            disabled={configure.isPending}
            onClick={() =>
              configure.mutate(
                {
                  id: action.id,
                  body: {
                    ...configOf(action),
                    label: label.trim() || null,
                    // Only store what differs from the declaration, so later platform changes still apply.
                    types: same(typeNames, action.declared.types) ? null : typeNames,
                    states: same(states, action.declared.states) ? null : states,
                  },
                },
                { onSuccess: onClose },
              )
            }
          >
            Save
          </button>
        </>
      }
    >
      <div style={{ display: "grid", gap: 14 }}>
        <ErrorBanner error={configure.error} />
        <div className="field">
          <label htmlFor="action-label">Label</label>
          <input id="action-label" value={label} placeholder={action.declared.label} onChange={(e) => setLabel(e.target.value)} />
          <span className="hint">Leave empty for the default, “{action.declared.label}”.</span>
        </div>
        <fieldset className="check-list">
          <legend>Types (subtypes included; none ticked = all types)</legend>
          {types
            ?.filter((t) => !t.parentId)
            .concat(types.filter((t) => t.parentId))
            .map((t) => (
              <label key={t.id} className="check">
                <input type="checkbox" checked={typeNames.includes(t.name)} onChange={() => toggle(typeNames, setTypeNames, t.name)} />
                {t.displayName} <code className="muted">{t.name}</code>
              </label>
            ))}
        </fieldset>
        <fieldset className="check-list">
          <legend>States (none ticked = every state)</legend>
          {stateKeys.map(([key, name]) => (
            <label key={key} className="check">
              <input type="checkbox" checked={states.includes(key)} onChange={() => toggle(states, setStates, key)} />
              {name} <code className="muted">{key}</code>
            </label>
          ))}
        </fieldset>
      </div>
    </Dialog>
  );
}
