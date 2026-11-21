import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, Lock, Pencil, Plus, Trash2, X } from "lucide-react";
import { useState, type FormEvent } from "react";
import { Link } from "react-router";
import { api, unwrap, type Schemas } from "../api/client";
import { useEnumerations, useObjectSearch, useTypes, type VersionedObject } from "../api/queries";
import { pickLabel } from "../components/AttributeForm";
import { ErrorBanner, Loading, Panel, StateBadge, TypeIcon } from "../components/common";
import { locale, t, tn } from "../i18n/i18n";
import { SpecPicker, StructureTree, useDefaultSpec, type Spec } from "./StructureTree";

// Product structure (roadmap 4.1): the parts a part uses, edited on its working copy.

type UsageLine = Schemas["UsageLine"];

export const useUsages = (objectId: string, workingCopy: boolean) =>
  useQuery({
    queryKey: ["usages", objectId, workingCopy],
    queryFn: () => unwrap(api.GET("/api/v1/objects/{id}/usages", { params: { path: { id: objectId }, query: { workingCopy } } })),
  });

/** The `unitOfUse` value list: keys and labels. */
export function useUnits(): { key: string; label: string }[] {
  const { data } = useEnumerations();
  const list = data && [...data.values()].find((e) => e.name === "unitOfUse");
  return (list?.values ?? []).filter((v) => v.active).map((v) => ({ key: v.key, label: pickLabel(v.labels, v.key) }));
}

const qty = (n: number) => n.toLocaleString(locale(), { maximumFractionDigits: 6 });

/**
 * The Structure tab of a part: the multi-level tree under a configuration specification, or, for
 * whoever has it checked out, the working copy's lines to edit.
 */
export function StructureTab({ obj, editable }: { obj: VersionedObject; editable: boolean }) {
  const preferred = useDefaultSpec(obj.contextId);
  const [chosen, setChosen] = useState<Spec | null>(null);
  if (editable) return <EditableStructure obj={obj} />;
  const spec = chosen ?? preferred;
  return (
    <Panel title={t("Structure")} actions={<SpecPicker spec={spec} onChange={setChosen} />} flush>
      <StructureTree key={JSON.stringify(spec)} obj={obj} spec={spec} />
    </Panel>
  );
}

function EditableStructure({ obj }: { obj: VersionedObject }) {
  const editable = true;
  const qc = useQueryClient();
  const { data, error, isLoading } = useUsages(obj.id, editable);
  const units = useUnits();
  const [editing, setEditing] = useState<string | null>(null);
  const refresh = () => void qc.invalidateQueries({ queryKey: ["usages", obj.id] });
  const remove = useMutation({
    mutationFn: (usageId: string) =>
      unwrap(api.DELETE("/api/v1/objects/{id}/usages/{usageId}", { params: { path: { id: obj.id, usageId } } })),
    onSuccess: refresh,
  });
  const unitLabel = (key: string) => units.find((u) => u.key === key)?.label ?? key;

  return (
    <Panel
      title={t("Structure")}
      note={
        editable
          ? t("Your working copy: changes become the part's at check-in")
          : data
            ? tn("{n} line", "{n} lines", data.length)
            : undefined
      }
      flush
    >
      <ErrorBanner error={error ?? remove.error} />
      {isLoading ? (
        <Loading />
      ) : (
        <div className="grid-wrap">
          <table className="grid structure" aria-label={t("Structure")}>
            <thead>
              <tr>
                <th className="num">{t("Line")}</th>
                <th>{t("Find no.")}</th>
                <th className="icon-cell" />
                <th>{t("Number")}</th>
                <th>{t("Name")}</th>
                <th>{t("Version")}</th>
                <th>{t("State")}</th>
                <th className="num">{t("Quantity")}</th>
                <th>{t("Unit")}</th>
                {editable && <th />}
              </tr>
            </thead>
            <tbody>
              {data?.map((l) =>
                editing === l.id ? (
                  <EditRow key={l.id} obj={obj} line={l} units={units} onDone={() => (setEditing(null), refresh())} />
                ) : (
                  <tr key={l.id}>
                    <td className="num">{l.lineNumber}</td>
                    <td>{l.findNumber ?? <span className="muted">—</span>}</td>
                    <td className="icon-cell">
                      {l.child.typeId ? <TypeIcon typeId={l.child.typeId} /> : <Lock size={14} className="icon-muted" />}
                    </td>
                    {l.child.readable ? (
                      <>
                        <td className="nowrap">
                          <Link className="num-link" to={`/objects/${l.child.id}`}>
                            {l.child.number}
                          </Link>
                        </td>
                        <td>{l.child.name}</td>
                        <td className="version">{l.child.version}</td>
                        <td>{l.child.state && <StateBadge state={l.child.state} />}</td>
                      </>
                    ) : (
                      <td colSpan={4} className="muted">
                        {t("A part you don't have access to")}
                      </td>
                    )}
                    <td className="num">{qty(l.quantity)}</td>
                    <td>{unitLabel(l.unit)}</td>
                    {editable && (
                      <td className="nowrap" style={{ width: 1 }}>
                        <button
                          className="icon-btn"
                          aria-label={t("Edit line {line}", { line: l.lineNumber })}
                          onClick={() => setEditing(l.id)}
                        >
                          <Pencil size={13} />
                        </button>
                        <button
                          className="icon-btn"
                          aria-label={t("Remove line {line}", { line: l.lineNumber })}
                          onClick={() => remove.mutate(l.id)}
                        >
                          <Trash2 size={13} />
                        </button>
                      </td>
                    )}
                  </tr>
                ),
              )}
              {data?.length === 0 && (
                <tr>
                  <td colSpan={editable ? 10 : 9} className="empty">
                    {editable ? t("No parts yet: add the first one below.") : t("This part uses no other parts.")}
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}
      {editable && <AddLine obj={obj} units={units} onAdded={refresh} />}
    </Panel>
  );
}

function EditRow({
  obj,
  line,
  units,
  onDone,
}: {
  obj: VersionedObject;
  line: UsageLine;
  units: { key: string; label: string }[];
  onDone: () => void;
}) {
  const [lineNumber, setLineNumber] = useState(String(line.lineNumber));
  const [find, setFind] = useState(line.findNumber ?? "");
  const [quantity, setQuantity] = useState(String(line.quantity));
  const [unit, setUnit] = useState(line.unit);
  const save = useMutation({
    mutationFn: () =>
      unwrap(
        api.PUT("/api/v1/objects/{id}/usages/{usageId}", {
          params: { path: { id: obj.id, usageId: line.id } },
          body: {
            lineNumber: Number(lineNumber),
            findNumber: find || undefined,
            quantity: Number(quantity),
            unit,
            notes: line.notes ?? undefined,
          },
        }),
      ),
    onSuccess: onDone,
  });
  return (
    <tr className="editing">
      <td>
        <input
          aria-label={t("Line")}
          className="narrow"
          value={lineNumber}
          onChange={(e) => setLineNumber(e.target.value)}
          inputMode="numeric"
        />
      </td>
      <td>
        <input aria-label={t("Find no.")} className="narrow" value={find} onChange={(e) => setFind(e.target.value)} />
      </td>
      <td className="icon-cell">{line.child.typeId && <TypeIcon typeId={line.child.typeId} />}</td>
      <td className="nowrap">{line.child.number ?? "—"}</td>
      <td colSpan={3}>
        {line.child.name}
        {save.error != null && <ErrorBanner error={save.error} />}
      </td>
      <td>
        <input
          aria-label={t("Quantity")}
          className="narrow num"
          value={quantity}
          onChange={(e) => setQuantity(e.target.value)}
          inputMode="decimal"
        />
      </td>
      <td>
        <select aria-label={t("Unit")} value={unit} onChange={(e) => setUnit(e.target.value)}>
          {units.map((u) => (
            <option key={u.key} value={u.key}>
              {u.label}
            </option>
          ))}
        </select>
      </td>
      <td className="nowrap">
        <button className="icon-btn" aria-label={t("Save")} onClick={() => save.mutate()} disabled={save.isPending}>
          <Check size={14} />
        </button>
        <button className="icon-btn" aria-label={t("Cancel")} onClick={onDone}>
          <X size={14} />
        </button>
      </td>
    </tr>
  );
}

/** Find a part by number or name and add it as a new line. */
function AddLine({ obj, units, onAdded }: { obj: VersionedObject; units: { key: string; label: string }[]; onAdded: () => void }) {
  const [text, setText] = useState("");
  const [chosen, setChosen] = useState<VersionedObject | null>(null);
  const [quantity, setQuantity] = useState("1");
  const [unit, setUnit] = useState("");
  const [find, setFind] = useState("");
  const { data: types } = useTypes();
  const search = useObjectSearch(chosen ? "" : text, 8);
  const isPart = (o: VersionedObject) => types?.find((ty) => ty.id === o.typeId)?.baseClass === "PART";
  const matches = (search.data?.pages.flat() ?? []).filter((o) => isPart(o) && o.id !== obj.id);
  const add = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/api/v1/objects/{id}/usages", {
          params: { path: { id: obj.id } },
          body: { childId: chosen!.id, quantity: Number(quantity), unit: unit || undefined, findNumber: find || undefined },
        }),
      ),
    onSuccess: () => {
      setChosen(null);
      setText("");
      setQuantity("1");
      setFind("");
      onAdded();
    },
  });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (chosen) add.mutate();
  };
  return (
    <form className="add-line" onSubmit={submit} aria-label={t("Add a part")}>
      <div className="add-line-pick">
        {chosen ? (
          <span className="chosen">
            <TypeIcon typeId={chosen.typeId} /> <strong>{chosen.number}</strong> {chosen.name}
            <button type="button" className="icon-btn" aria-label={t("Choose another part")} onClick={() => setChosen(null)}>
              <X size={12} />
            </button>
          </span>
        ) : (
          <input
            aria-label={t("Part to add")}
            placeholder={t("Find a part by number or name")}
            value={text}
            onChange={(e) => setText(e.target.value)}
          />
        )}
        {!chosen && text.trim() && (
          <ul className="add-line-results" role="listbox" aria-label={t("Matching parts")}>
            {matches.length === 0 && !search.isFetching && <li className="muted">{t("No part matches")}</li>}
            {matches.map((o) => (
              <li key={o.id} role="option" aria-selected={false} onClick={() => setChosen(o)}>
                <TypeIcon typeId={o.typeId} /> <strong>{o.number}</strong> {o.name} <span className="muted">{o.version}</span>
              </li>
            ))}
          </ul>
        )}
      </div>
      <input
        aria-label={t("Quantity")}
        className="narrow num"
        value={quantity}
        onChange={(e) => setQuantity(e.target.value)}
        inputMode="decimal"
      />
      <select aria-label={t("Unit")} value={unit} onChange={(e) => setUnit(e.target.value)}>
        <option value="">{t("Its default unit")}</option>
        {units.map((u) => (
          <option key={u.key} value={u.key}>
            {u.label}
          </option>
        ))}
      </select>
      <input
        aria-label={t("Find no.")}
        className="narrow"
        placeholder={t("Find no.")}
        value={find}
        onChange={(e) => setFind(e.target.value)}
      />
      <button className="btn primary" type="submit" disabled={!chosen || add.isPending}>
        <Plus size={14} aria-hidden /> {t("Add")}
      </button>
      {add.error != null && (
        <div style={{ flexBasis: "100%" }}>
          <ErrorBanner error={add.error} />
        </div>
      )}
    </form>
  );
}
