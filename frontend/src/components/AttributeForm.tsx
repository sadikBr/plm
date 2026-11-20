import { Info } from "lucide-react";
import type { ReactNode } from "react";
import { ApiError } from "../api/client";
import { Props } from "./common";
import {
  useEnumerations,
  useUnits,
  type AttributeDefinition,
  type Attributes,
  type Enumeration,
  type Unit,
} from "../api/queries";

// ---------- Helpers ----------

/** Best label for the user's language, falling back to English, then the internal name. */
export function pickLabel(labels: Record<string, string> | undefined, fallback = ""): string {
  if (!labels) return fallback;
  const lang = (typeof navigator !== "undefined" ? navigator.language : "en").toLowerCase();
  return labels[lang] ?? labels[lang.split("-")[0]!] ?? labels.en ?? Object.values(labels)[0] ?? fallback;
}

/** Field errors from an INVALID_ATTRIBUTES problem, keyed by attribute name. */
export function fieldErrors(error: unknown): Record<string, string> {
  if (!(error instanceof ApiError) || error.code !== "INVALID_ATTRIBUTES") return {};
  const list = (error.problem?.details?.errors ?? []) as { attribute: string; message: string }[];
  return Object.fromEntries(list.map((e) => [e.attribute, e.message]));
}

/** Values to start a create form with: each attribute's default. */
export function defaultValues(definitions: AttributeDefinition[]): Attributes {
  return Object.fromEntries(
    definitions.filter((d) => d.active && d.defaultValue != null).map((d) => [d.name, d.defaultValue]),
  );
}

interface Quantity {
  value: number;
  unit: string;
}

const isQuantity = (v: unknown): v is Quantity =>
  typeof v === "object" && v !== null && "value" in v && "unit" in v;

/** ISO instant ⇄ the local "YYYY-MM-DDTHH:mm" an <input type="datetime-local"> uses. */
export function toLocalInput(iso: unknown): string {
  if (typeof iso !== "string" || !iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  const local = new Date(d.getTime() - d.getTimezoneOffset() * 60_000);
  return local.toISOString().slice(0, 16);
}

export function fromLocalInput(local: string): string | undefined {
  if (!local) return undefined;
  const d = new Date(local);
  return Number.isNaN(d.getTime()) ? undefined : d.toISOString();
}

/** Human-readable value, with enumeration labels and units. */
export function formatAttribute(
  def: AttributeDefinition | undefined,
  value: unknown,
  enumerations?: Map<string, Enumeration>,
): string {
  if (value === null || value === undefined) return "—";
  if (Array.isArray(value)) return value.map((v) => formatAttribute(def, v, enumerations)).join(", ");
  if (isQuantity(value)) return `${value.value} ${value.unit}`;
  if (typeof value === "boolean") return value ? "Yes" : "No";
  if (def?.dataType === "ENUMERATION" && def.enumerationId) {
    const entry = enumerations?.get(def.enumerationId)?.values.find((v) => v.key === value);
    if (entry) return pickLabel(entry.labels, String(value));
  }
  if (def?.dataType === "TIMESTAMP" && typeof value === "string") {
    return new Date(value).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
  }
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

// ---------- Read-only view ----------

/** Attribute values as a property grid, in the type's order. Values without a definition (legacy data) come last. */
export function AttributeValues({ definitions, values }: { definitions: AttributeDefinition[]; values: Attributes }) {
  const { data: enumerations } = useEnumerations();
  const known = new Set(definitions.map((d) => d.name));
  const items: [string, ReactNode][] = [
    ...definitions
      .filter((d) => d.active || values[d.name] != null)
      .map((d): [string, ReactNode] => [pickLabel(d.labels, d.name), values[d.name] == null ? null : formatAttribute(d, values[d.name], enumerations)]),
    ...Object.keys(values)
      .filter((k) => !known.has(k))
      .map((k): [string, ReactNode] => [k, formatAttribute(undefined, values[k])]),
  ];
  if (items.length === 0) return <p className="muted">No attributes.</p>;
  return <Props items={items} columns={2} />;
}

// ---------- Editable form ----------

/**
 * One input per active attribute, chosen by data type. Controlled: [values] in, [onChange] out.
 * [errors] are server-side messages keyed by attribute name (see [fieldErrors]).
 */
export function AttributeForm({
  definitions,
  values,
  onChange,
  errors = {},
  idPrefix = "attr",
}: {
  definitions: AttributeDefinition[];
  values: Attributes;
  onChange: (values: Attributes) => void;
  errors?: Record<string, string>;
  idPrefix?: string;
}) {
  const { data: enumerations } = useEnumerations();
  const { data: units } = useUnits();
  const set = (name: string, value: unknown) => {
    const next = { ...values };
    if (value === undefined || value === "" || (Array.isArray(value) && value.length === 0)) delete next[name];
    else next[name] = value;
    onChange(next);
  };

  return (
    <div className="attr-form">
      {definitions
        .filter((d) => d.active)
        .map((def) => {
          const id = `${idPrefix}-${def.name}`;
          const error = errors[def.name];
          return (
            <div className="field" key={def.name}>
              {def.dataType !== "BOOLEAN" && (
                // The asterisk stays outside the label: the input's `required` attribute is what assistive tech announces.
                <div className="label-row">
                  <label htmlFor={id}>{pickLabel(def.labels, def.name)}</label>
                  {def.required && <span aria-hidden className="required">*</span>}
                  {def.description && (
                    <span title={def.description} className="info">
                      <Info size={12} aria-hidden />
                    </span>
                  )}
                </div>
              )}
              {def.description && (
                <span id={`${id}-desc`} className="sr-only">
                  {def.description}
                </span>
              )}
              <Input
                id={id}
                def={def}
                value={values[def.name]}
                onChange={(v) => set(def.name, v)}
                enumeration={def.enumerationId ? enumerations?.get(def.enumerationId) : undefined}
                units={units ?? []}
                invalid={!!error}
              />
              {error && (
                <span className="field-error" role="alert">
                  {error}
                </span>
              )}
            </div>
          );
        })}
    </div>
  );
}

function Input({
  id,
  def,
  value,
  onChange,
  enumeration,
  units,
  invalid,
}: {
  id: string;
  def: AttributeDefinition;
  value: unknown;
  onChange: (value: unknown) => void;
  enumeration?: Enumeration;
  units: Unit[];
  invalid: boolean;
}): ReactNode {
  const common = {
    id,
    "aria-invalid": invalid || undefined,
    "aria-describedby": def.description ? `${id}-desc` : undefined,
    required: def.required,
  };
  const str = (v: unknown) => (v === undefined || v === null ? "" : String(v));

  if (def.multiValued) {
    if (def.dataType === "ENUMERATION") {
      const chosen = new Set(Array.isArray(value) ? (value as string[]) : []);
      return (
        <div id={id} role="group" aria-label={pickLabel(def.labels, def.name)} className="checks">
          {(enumeration?.values ?? [])
            .filter((v) => v.active !== false || chosen.has(v.key))
            .map((v) => (
              <label key={v.key} className="check">
                <input
                  type="checkbox"
                  checked={chosen.has(v.key)}
                  onChange={(e) => {
                    const next = new Set(chosen);
                    if (e.target.checked) next.add(v.key);
                    else next.delete(v.key);
                    onChange([...next]);
                  }}
                />
                {pickLabel(v.labels, v.key)}
              </label>
            ))}
        </div>
      );
    }
    const lines = Array.isArray(value) ? value.map((v) => (isQuantity(v) ? `${v.value} ${v.unit}` : str(v))).join("\n") : "";
    return (
      <>
        <textarea
          {...common}
          value={lines}
          onChange={(e) =>
            onChange(
              e.target.value
                .split("\n")
                .map((l) => l.trim())
                .filter(Boolean)
                .map((l) => (def.dataType === "INTEGER" || def.dataType === "REAL" ? Number(l) : l)),
            )
          }
        />
        <span className="hint">One value per line.</span>
      </>
    );
  }

  switch (def.dataType) {
    case "TEXT":
      return <textarea {...common} value={str(value)} maxLength={def.maxLength ?? undefined} onChange={(e) => onChange(e.target.value)} />;
    case "INTEGER":
    case "REAL":
      return (
        <input
          {...common}
          type="number"
          step={def.dataType === "INTEGER" ? 1 : "any"}
          min={def.minValue ?? undefined}
          max={def.maxValue ?? undefined}
          value={str(value)}
          onChange={(e) => onChange(e.target.value === "" ? undefined : Number(e.target.value))}
        />
      );
    case "QUANTITY": {
      const q = isQuantity(value) ? value : undefined;
      const choices = units.filter((u) => u.dimension === def.dimension);
      const unit = q?.unit ?? def.displayUnit ?? choices.find((u) => Number(u.factor) === 1)?.code ?? choices[0]?.code ?? "";
      return (
        <div className="row">
          <input
            {...common}
            type="number"
            step="any"
            value={q ? String(q.value) : ""}
            onChange={(e) => onChange(e.target.value === "" ? undefined : { value: Number(e.target.value), unit })}
          />
          <select
            aria-label={`${pickLabel(def.labels, def.name)} unit`}
            value={unit}
            onChange={(e) => q && onChange({ value: q.value, unit: e.target.value })}
            style={{ flex: "0 0 auto", width: "auto" }}
          >
            {choices.map((u) => (
              <option key={u.code} value={u.code}>
                {u.code}
              </option>
            ))}
          </select>
        </div>
      );
    }
    case "BOOLEAN":
      return (
        <label className="check" title={def.description ?? undefined}>
          <input
            id={id}
            type="checkbox"
            aria-describedby={def.description ? `${id}-desc` : undefined}
            checked={value === true}
            onChange={(e) => onChange(e.target.checked)}
          />
          {pickLabel(def.labels, def.name)}
        </label>
      );
    case "DATE":
      return <input {...common} type="date" value={str(value)} onChange={(e) => onChange(e.target.value || undefined)} />;
    case "TIMESTAMP":
      return (
        <input {...common} type="datetime-local" value={toLocalInput(value)} onChange={(e) => onChange(fromLocalInput(e.target.value))} />
      );
    case "URL":
      return <input {...common} type="url" value={str(value)} onChange={(e) => onChange(e.target.value)} placeholder="https://" />;
    case "ENUMERATION": {
      const options = (enumeration?.values ?? []).filter((v) => v.active !== false || v.key === value);
      return (
        <select {...common} value={str(value)} onChange={(e) => onChange(e.target.value || undefined)}>
          {(!def.required || value == null) && <option value="">—</option>}
          {options.map((v) => (
            <option key={v.key} value={v.key}>
              {pickLabel(v.labels, v.key)}
            </option>
          ))}
        </select>
      );
    }
    default:
      return (
        <input
          {...common}
          type="text"
          value={str(value)}
          maxLength={def.maxLength ?? undefined}
          pattern={def.pattern ?? undefined}
          onChange={(e) => onChange(e.target.value)}
        />
      );
  }
}
