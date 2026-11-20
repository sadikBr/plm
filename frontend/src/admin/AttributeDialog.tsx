import { useMemo, useState, type FormEvent } from "react";
import type { AttributeDefinition, Attributes, DataType } from "../api/queries";
import { AttributeForm, pickLabel } from "../components/AttributeForm";
import { Dialog, ErrorBanner } from "../components/common";
import { useDefineAttribute, useEnumerationList, useUnitList, useUpdateAttribute, type TypeDefinition } from "./adminQueries";

const DATA_TYPES: { key: DataType; label: string }[] = [
  { key: "STRING", label: "Text" },
  { key: "TEXT", label: "Long text" },
  { key: "INTEGER", label: "Whole number" },
  { key: "REAL", label: "Decimal number" },
  { key: "QUANTITY", label: "Quantity with unit" },
  { key: "BOOLEAN", label: "Yes / no" },
  { key: "DATE", label: "Date" },
  { key: "TIMESTAMP", label: "Date and time" },
  { key: "URL", label: "Web link" },
  { key: "ENUMERATION", label: "Choice from a value list" },
];

export function dataTypeLabel(a: AttributeDefinition): string {
  const base = DATA_TYPES.find((d) => d.key === a.dataType)?.label ?? a.dataType;
  const detail = a.dataType === "QUANTITY" ? ` (${(a.dimension ?? "").toLowerCase()}${a.displayUnit ? `, ${a.displayUnit}` : ""})` : "";
  return `${base}${detail}${a.multiValued ? ", several values" : ""}`;
}

export function constraintSummary(a: AttributeDefinition): string {
  const parts: string[] = [];
  if (a.required) parts.push("Required");
  if (a.immutable) parts.push("Fixed after creation");
  if (a.minLength != null || a.maxLength != null) parts.push(`Length ${a.minLength ?? 0}–${a.maxLength ?? "∞"}`);
  if (a.pattern) parts.push(`Pattern ${a.pattern}`);
  if (a.minValue != null || a.maxValue != null) parts.push(`Range ${a.minValue ?? "−∞"} – ${a.maxValue ?? "∞"}`);
  if (a.defaultValue != null) parts.push(`Default ${typeof a.defaultValue === "object" ? JSON.stringify(a.defaultValue) : String(a.defaultValue)}`);
  return parts.join(" · ");
}

const TEXTUAL = new Set<DataType>(["STRING", "TEXT"]);
const NUMERIC = new Set<DataType>(["INTEGER", "REAL", "QUANTITY"]);

const numberOrNull = (s: string) => (s.trim() === "" || Number.isNaN(Number(s)) ? null : Number(s));
const intOrNull = (s: string) => (s.trim() === "" ? null : Math.trunc(Number(s)));
const str = (v: unknown) => (v === null || v === undefined ? "" : String(v));

/** Define a new attribute on [type], or edit [attribute] (on whichever type defines it). */
export function AttributeDialog({
  type,
  attribute,
  definedOn,
  onClose,
}: {
  type: TypeDefinition;
  attribute?: AttributeDefinition;
  definedOn: string;
  onClose: () => void;
}) {
  const editing = !!attribute;
  const define = useDefineAttribute(type.id);
  const update = useUpdateAttribute(attribute?.typeId ?? type.id);
  const mutation = editing ? update : define;
  const { data: units } = useUnitList();
  const { data: lists } = useEnumerationList();

  const [name, setName] = useState(attribute?.name ?? "");
  const [label, setLabel] = useState(attribute ? pickLabel(attribute.labels, attribute.name) : "");
  const [description, setDescription] = useState(attribute?.description ?? "");
  const [dataType, setDataType] = useState<DataType>(attribute?.dataType ?? "STRING");
  const [multiValued, setMultiValued] = useState(attribute?.multiValued ?? false);
  const [enumerationId, setEnumerationId] = useState(attribute?.enumerationId ?? "");
  const [dimension, setDimension] = useState(attribute?.dimension ?? "");
  const [displayUnit, setDisplayUnit] = useState(attribute?.displayUnit ?? "");
  const [required, setRequired] = useState(attribute?.required ?? false);
  const [immutable, setImmutable] = useState(attribute?.immutable ?? false);
  const [minLength, setMinLength] = useState(str(attribute?.minLength));
  const [maxLength, setMaxLength] = useState(str(attribute?.maxLength));
  const [pattern, setPattern] = useState(attribute?.pattern ?? "");
  const [minValue, setMinValue] = useState(str(attribute?.minValue));
  const [maxValue, setMaxValue] = useState(str(attribute?.maxValue));
  const [defaultValue, setDefaultValue] = useState<unknown>(attribute?.defaultValue ?? null);
  const [active, setActive] = useState(attribute?.active ?? true);

  // While creating, the name follows the label (camelCase) until edited.
  const [nameEdited, setNameEdited] = useState(editing);
  const derivedName = label
    .replace(/[^A-Za-z0-9]+/g, " ")
    .trim()
    .split(" ")
    .filter(Boolean)
    .map((w, i) => (i === 0 ? w[0]!.toLowerCase() + w.slice(1) : w[0]!.toUpperCase() + w.slice(1).toLowerCase()))
    .join("");
  const effectiveName = nameEdited ? name : derivedName;
  const dimensions = useMemo(() => [...new Set((units ?? []).map((u) => u.dimension))].sort(), [units]);

  // The default value is edited with the same input the users will get.
  const preview: AttributeDefinition = {
    ...(attribute ?? {
      id: "preview",
      typeId: type.id,
      inherited: false,
      system: false,
      sortOrder: 0,
    }),
    name: "defaultValue",
    labels: { en: "Default value" },
    description: null,
    dataType,
    multiValued,
    required: false,
    immutable: false,
    minLength: null,
    maxLength: intOrNull(maxLength),
    pattern: null,
    minValue: null,
    maxValue: null,
    enumerationId: enumerationId || null,
    dimension: dimension || null,
    displayUnit: displayUnit || null,
    defaultValue: null,
    active: true,
  } as AttributeDefinition;

  const settings = {
    labels: { ...(attribute?.labels ?? {}), en: label.trim() },
    description: description.trim() || null,
    required,
    immutable,
    minLength: TEXTUAL.has(dataType) ? intOrNull(minLength) : null,
    maxLength: TEXTUAL.has(dataType) || dataType === "URL" ? intOrNull(maxLength) : null,
    pattern: TEXTUAL.has(dataType) ? pattern.trim() || null : null,
    minValue: NUMERIC.has(dataType) ? numberOrNull(minValue) : null,
    maxValue: NUMERIC.has(dataType) ? numberOrNull(maxValue) : null,
    displayUnit: dataType === "QUANTITY" ? displayUnit || null : null,
    defaultValue: defaultValue ?? null,
    sortOrder: attribute?.sortOrder ?? 0,
  };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const done = { onSuccess: onClose };
    if (attribute) {
      update.mutate({ attributeId: attribute.id, body: { ...settings, active } }, done);
    } else {
      define.mutate(
        {
          ...settings,
          name: effectiveName,
          dataType,
          multiValued,
          enumerationId: dataType === "ENUMERATION" ? enumerationId || null : null,
          dimension: dataType === "QUANTITY" ? dimension || null : null,
        },
        done,
      );
    }
  };

  const problems = (mutation.error as { problem?: { details?: { errors?: string[] } } } | null)?.problem?.details?.errors;

  return (
    <Dialog
      open
      title={editing ? `Edit attribute: ${pickLabel(attribute.labels, attribute.name)}` : `New attribute on ${type.displayName}`}
      onClose={onClose}
      footer={
        <>
          <button className="btn" type="button" onClick={onClose}>
            Cancel
          </button>
          <button className="btn primary" type="submit" form="attribute-form" disabled={mutation.isPending || !label.trim() || !effectiveName}>
            {editing ? "Save" : "Add attribute"}
          </button>
        </>
      }
    >
      <form id="attribute-form" onSubmit={submit} noValidate style={{ display: "grid", gap: 12 }}>
        {problems ? (
          <div className="banner error" role="alert">
            <ul style={{ margin: 0, paddingLeft: 16 }}>
              {problems.map((p) => (
                <li key={p}>{p}</li>
              ))}
            </ul>
          </div>
        ) : (
          <ErrorBanner error={mutation.error} />
        )}
        {editing && attribute.typeId !== type.id && (
          <div className="banner info">Defined on {definedOn}: changes apply to it and all its subtypes.</div>
        )}
        <div className="attr-form cols-2">
          <div className="field">
            <label htmlFor="attr-label">Label</label>
            <input id="attr-label" value={label} onChange={(e) => setLabel(e.target.value)} autoFocus required />
          </div>
          <div className="field">
            <label htmlFor="attr-name">Name</label>
            <input
              id="attr-name"
              value={effectiveName}
              disabled={editing}
              onChange={(e) => {
                setNameEdited(true);
                setName(e.target.value);
              }}
            />
            {!editing && <span className="hint">camelCase; used by the API. Can't be changed later.</span>}
          </div>
          <div className="field wide">
            <label htmlFor="attr-description">Description</label>
            <input id="attr-description" value={description} onChange={(e) => setDescription(e.target.value)} placeholder="Shown as help next to the field" />
          </div>
          <div className="field">
            <label htmlFor="attr-type">Data type</label>
            <select id="attr-type" value={dataType} disabled={editing} onChange={(e) => setDataType(e.target.value as DataType)}>
              {DATA_TYPES.map((d) => (
                <option key={d.key} value={d.key}>
                  {d.label}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <span className="label-spacer" aria-hidden />
            <label className="check">
              <input type="checkbox" checked={multiValued} disabled={editing || dataType === "BOOLEAN"} onChange={(e) => setMultiValued(e.target.checked)} />
              Several values
            </label>
          </div>
          {dataType === "ENUMERATION" && (
            <div className="field wide">
              <label htmlFor="attr-list">Value list</label>
              <select id="attr-list" value={enumerationId} disabled={editing} onChange={(e) => setEnumerationId(e.target.value)}>
                <option value="">Choose…</option>
                {lists?.map((l) => (
                  <option key={l.id} value={l.id}>
                    {pickLabel(l.labels, l.name)} ({l.values.filter((v) => v.active !== false).length} values)
                  </option>
                ))}
              </select>
              {!editing && <span className="hint">Create lists under Value lists.</span>}
            </div>
          )}
          {dataType === "QUANTITY" && (
            <>
              <div className="field">
                <label htmlFor="attr-dimension">Measures</label>
                <select
                  id="attr-dimension"
                  value={dimension}
                  disabled={editing}
                  onChange={(e) => {
                    setDimension(e.target.value);
                    setDisplayUnit("");
                  }}
                >
                  <option value="">Choose…</option>
                  {dimensions.map((d) => (
                    <option key={d} value={d}>
                      {d.charAt(0) + d.slice(1).toLowerCase().replace(/_/g, " ")}
                    </option>
                  ))}
                </select>
              </div>
              <div className="field">
                <label htmlFor="attr-unit">Display unit</label>
                <select id="attr-unit" value={displayUnit} onChange={(e) => setDisplayUnit(e.target.value)}>
                  <option value="">Base unit</option>
                  {(units ?? [])
                    .filter((u) => u.dimension === dimension)
                    .map((u) => (
                      <option key={u.code} value={u.code}>
                        {u.code} — {pickLabel(u.labels, u.code)}
                      </option>
                    ))}
                </select>
              </div>
            </>
          )}
        </div>

        <fieldset className="attr-group">
          <legend className="section-label">Rules</legend>
          <div className="attr-form cols-2">
            <label className="check">
              <input type="checkbox" checked={required} onChange={(e) => setRequired(e.target.checked)} />
              Required
            </label>
            <label className="check">
              <input type="checkbox" checked={immutable} onChange={(e) => setImmutable(e.target.checked)} />
              Fixed once the object is created
            </label>
            {TEXTUAL.has(dataType) && (
              <>
                <div className="field">
                  <label htmlFor="attr-minlen">Minimum length</label>
                  <input id="attr-minlen" type="number" min={0} value={minLength} onChange={(e) => setMinLength(e.target.value)} />
                </div>
                <div className="field">
                  <label htmlFor="attr-maxlen">Maximum length</label>
                  <input id="attr-maxlen" type="number" min={1} value={maxLength} onChange={(e) => setMaxLength(e.target.value)} />
                </div>
                <div className="field wide">
                  <label htmlFor="attr-pattern">Pattern (regular expression)</label>
                  <input id="attr-pattern" value={pattern} onChange={(e) => setPattern(e.target.value)} placeholder="e.g. M\d+(x[\d.]+)?" />
                </div>
              </>
            )}
            {dataType === "URL" && (
              <div className="field">
                <label htmlFor="attr-maxlen">Maximum length</label>
                <input id="attr-maxlen" type="number" min={1} value={maxLength} onChange={(e) => setMaxLength(e.target.value)} />
              </div>
            )}
            {NUMERIC.has(dataType) && (
              <>
                <div className="field">
                  <label htmlFor="attr-min">Minimum{dataType === "QUANTITY" ? " (base unit)" : ""}</label>
                  <input id="attr-min" inputMode="decimal" value={minValue} onChange={(e) => setMinValue(e.target.value)} />
                </div>
                <div className="field">
                  <label htmlFor="attr-max">Maximum{dataType === "QUANTITY" ? " (base unit)" : ""}</label>
                  <input id="attr-max" inputMode="decimal" value={maxValue} onChange={(e) => setMaxValue(e.target.value)} />
                </div>
              </>
            )}
          </div>
          {(dataType !== "ENUMERATION" || enumerationId) && (dataType !== "QUANTITY" || dimension) && (
            <AttributeForm
              idPrefix="attr-default"
              definitions={[preview]}
              values={defaultValue == null ? {} : { defaultValue }}
              onChange={(v: Attributes) => setDefaultValue(v.defaultValue ?? null)}
            />
          )}
        </fieldset>

        {editing && (
          <label className="check">
            <input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} />
            Active. Inactive attributes disappear from forms; values already stored are kept and still shown.
          </label>
        )}
      </form>
    </Dialog>
  );
}
