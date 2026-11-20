import { Pencil, Plus, X } from "lucide-react";
import { useState } from "react";
import { useTypes, type AttributeDefinition } from "../api/queries";
import { pickLabel } from "../components/AttributeForm";
import { ErrorBanner, Loading, Panel, typeLabel } from "../components/common";
import { useGlobalAttributes, useGlobalUse } from "./adminQueries";
import { AttributeDialog, constraintSummary, dataTypeLabel } from "./AttributeDialog";

/** Attributes defined once and shared by several types, with the types that use them. */
export function GlobalAttributesAdmin() {
  const { data: globals, isLoading, error } = useGlobalAttributes();
  const { data: types } = useTypes();
  const use = useGlobalUse();
  const [editing, setEditing] = useState<AttributeDefinition | "new" | null>(null);

  if (isLoading) return <Loading />;
  return (
    <Panel
      title="Global attributes"
      note="Defined once, used by any number of types (and their subtypes); a change applies everywhere"
      flush
      actions={
        <button className="btn small primary" onClick={() => setEditing("new")}>
          <Plus size={13} aria-hidden />
          New global attribute
        </button>
      }
    >
      <ErrorBanner error={error ?? use.error} />
      {!globals?.length ? (
        <p className="empty">No global attributes yet. Define one, then add it to types here or on a type's Attributes tab.</p>
      ) : (
        <table className="grid" aria-label="Global attributes">
          <thead>
            <tr>
              <th>Label</th>
              <th>Name</th>
              <th>Data type</th>
              <th>Constraints</th>
              <th>Used by</th>
              <th aria-label="Actions" />
            </tr>
          </thead>
          <tbody>
            {globals.map((g) => {
              const a = g.attribute;
              const unused = (types ?? []).filter((t) => !g.usedBy.includes(t.id));
              return (
                <tr key={a.id} className={a.active ? undefined : "inactive"}>
                  <td className="nowrap">{pickLabel(a.labels, a.name)}</td>
                  <td className="nowrap">
                    <code>{a.name}</code>
                  </td>
                  <td className="nowrap">{dataTypeLabel(a)}</td>
                  <td className="muted">{constraintSummary(a) || "—"}</td>
                  <td>
                    <div className="row wrap" style={{ gap: 4 }}>
                      {g.usedBy.map((id) => {
                        const t = types?.find((x) => x.id === id);
                        return (
                          <span key={id} className="tag chip-x">
                            {typeLabel(t)}
                            <button
                              aria-label={`Stop using ${pickLabel(a.labels, a.name)} on ${typeLabel(t)}`}
                              onClick={() => use.mutate({ typeId: id, attributeId: a.id, use: false })}
                            >
                              <X size={11} />
                            </button>
                          </span>
                        );
                      })}
                      {unused.length > 0 && (
                        <select
                          aria-label={`Add ${pickLabel(a.labels, a.name)} to a type`}
                          value=""
                          style={{ width: 150, height: 24, minHeight: 24 }}
                          onChange={(e) => e.target.value && use.mutate({ typeId: e.target.value, attributeId: a.id, use: true })}
                        >
                          <option value="">Add to type…</option>
                          {unused.map((t) => (
                            <option key={t.id} value={t.id}>
                              {typeLabel(t)}
                            </option>
                          ))}
                        </select>
                      )}
                    </div>
                  </td>
                  <td style={{ width: 40 }}>
                    <button className="btn small ghost icon" aria-label={`Edit ${pickLabel(a.labels, a.name)}`} onClick={() => setEditing(a)}>
                      <Pencil size={13} aria-hidden />
                    </button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
      {editing && (
        <AttributeDialog global attribute={editing === "new" ? undefined : editing} definedOn="" onClose={() => setEditing(null)} />
      )}
    </Panel>
  );
}
