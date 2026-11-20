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
  type LayoutGroup,
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

/** A calculated value as text; empty until the object is saved (or when its formula has no result). */
function formatCalculated(value: unknown): string {
  if (value == null) return "Computed when saved";
  if (typeof value === "object" && value && "value" in value) {
    const q = value as { value: unknown; unit?: string };
    return `${String(q.value)} ${q.unit ?? ""}`.trim();
  }
  return String(value);
}

/** Values to start a create form with: each attribute's default. */
export function defaultValues(definitions: AttributeDefinition[]): Attributes {
  return Object.fromEntries(definitions.filter((d) => d.active && d.defaultValue != null).map((d) => [d.name, d.defaultValue]));
}

interface Quantity {
  value: number;
  unit: string;
}

const isQuantity = (v: unknown): v is Quantity => typeof v === "object" && v !== null && "value" in v && "unit" in v;

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
export function formatAttribute(def: AttributeDefinition | undefined, value: unknown, enumerations?: Map<string, Enumeration>): string {
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

/**
 * Attribute values as property grids, one per layout group (or one grid in the type's order when
 * there's no layout). Values of deactivated attributes and values without a definition (legacy
 * data) come last, under "Other values", so nothing stored is ever invisible.
 */
export function AttributeValues({
  definitions,
  values,
  groups,
}: {
  definitions: AttributeDefinition[];
  values: Attributes;
  groups?: LayoutGroup[];
}) {
  const { data: enumerations } = useEnumerations();
  const byName = new Map(definitions.map((d) => [d.name, d]));
  const item = (name: string): [string, ReactNode] => {
    const def = byName.get(name);
    const value = values[name];
    return [def ? pickLabel(def.labels, name) : name, value == null ? null : formatAttribute(def, value, enumerations)];
  };
  const sections: Section[] = groups
    ? groups.map((g) => ({
        key: g.key,
        title: pickLabel(g.labels, g.key),
        columns: g.columns,
        names: g.attributes.filter((n) => byName.get(n)?.active),
      }))
    : [{ key: "attributes", title: "Attributes", columns: 2, names: definitions.filter((d) => d.active).map((d) => d.name) }];
  const other = [
    ...definitions.filter((d) => !d.active && values[d.name] != null).map((d) => d.name),
    ...Object.keys(values).filter((k) => !byName.has(k)),
  ];
  if (other.length > 0) sections.push({ key: "other", title: "Other values", columns: 2, names: other });
  const shown = sections.filter((section) => section.names.length > 0);

  if (shown.length === 0) return <p className="muted">No attributes.</p>;
  // A single group needs no heading: the panel title says it all.
  if (shown.length === 1 && shown[0]!.key !== "other") {
    return <Props items={shown[0]!.names.map(item)} columns={shown[0]!.columns === 1 ? 1 : 2} />;
  }
  return (
    <div className="prop-groups">
      {shown.map((section) => (
        <section key={section.key} aria-label={section.title}>
          <h3 className="group-title">{section.title}</h3>
          <Props items={section.names.map(item)} columns={section.columns === 1 ? 1 : 2} />
        </section>
      ))}
    </div>
  );
}

interface Section {
  key: string;
  title: string;
  columns: number;
  names: string[];
}

// ---------- Editable form ----------

/**
 * For a dependent value list, only the values allowed with the parent's current value (the server
 * checks the same rule). A value already chosen stays listed, so it can be seen and changed.
 */
function allowedValues(def: AttributeDefinition, enumeration: Enumeration | undefined, values: Attributes): Enumeration | undefined {
  if (!enumeration || !def.dependsOn || !def.valueFilter) return enumeration;
  const parent = values[def.dependsOn];
  const allowed = typeof parent === "string" ? def.valueFilter[parent] : undefined;
  if (!allowed) return enumeration;
  const current = values[def.name];
  const keep = new Set([...allowed, ...(Array.isArray(current) ? current : [current])]);
  return { ...enumeration, values: enumeration.values.filter((v) => keep.has(v.key)) };
}

/**
 * One input per attribute, chosen by data type. With [groups] (a resolved layout) the fields are
 * grouped and ordered by it, and attributes it hides are not shown (their values, such as defaults,
 * are kept). Without, every active attribute is shown in the type's order.
 *
 * Controlled: [values] in, [onChange] out. [errors] are server-side messages keyed by attribute
 * name (see [fieldErrors]); errors on attributes the form doesn't show are listed above it.
 */
export function AttributeForm({
  definitions,
  values,
  onChange,
  errors = {},
  idPrefix = "attr",
  groups,
}: {
  definitions: AttributeDefinition[];
  values: Attributes;
  onChange: (values: Attributes) => void;
  errors?: Record<string, string>;
  idPrefix?: string;
  groups?: LayoutGroup[];
}) {
  const { data: enumerations } = useEnumerations();
  const { data: units } = useUnits();
  const set = (name: string, value: unknown) => {
    const next = { ...values };
    if (value === undefined || value === "" || (Array.isArray(value) && value.length === 0)) delete next[name];
    else next[name] = value;
    onChange(next);
  };

  const byName = new Map(definitions.map((d) => [d.name, d]));
  const field = (def: AttributeDefinition) => {
    const id = `${idPrefix}-${def.name}`;
    const error = errors[def.name];
    return (
      <div className="field" key={def.name}>
        {def.dataType !== "BOOLEAN" && (
          // The asterisk stays outside the label: the input's `required` attribute is what assistive tech announces.
          <div className="label-row">
            <label htmlFor={id}>{pickLabel(def.labels, def.name)}</label>
            {def.required && (
              <span aria-hidden className="required">
                *
              </span>
            )}
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
        {def.formula ? (
          // Calculated: the server computes it on save, so it's shown, not edited.
          <output id={id} className="calc-value" title={`Calculated: ${def.formula}`}>
            {formatCalculated(values[def.name])}
            <span className="tag">Calculated</span>
          </output>
        ) : (
          <Input
            id={id}
            def={def}
            value={values[def.name]}
            onChange={(v) => set(def.name, v)}
            enumeration={def.enumerationId ? allowedValues(def, enumerations?.get(def.enumerationId), values) : undefined}
            units={units ?? []}
            invalid={!!error}
          />
        )}
        {error && (
          <span className="field-error" role="alert">
            {error}
          </span>
        )}
      </div>
    );
  };

  if (!groups) return <div className="attr-form">{definitions.filter((d) => d.active).map(field)}</div>;

  const shown = new Set(groups.flatMap((g) => g.attributes));
  const unshown = Object.entries(errors).filter(([name]) => !shown.has(name));
  return (
    <div className="attr-groups">
      {unshown.length > 0 && (
        <div className="banner error" role="alert">
          {unshown.map(([name, message]) => `${pickLabel(byName.get(name)?.labels, name)}: ${message}`).join(" · ")}
        </div>
      )}
      {groups.map((group) => {
        const defs = group.attributes.map((n) => byName.get(n)).filter((d): d is AttributeDefinition => !!d?.active);
        if (defs.length === 0) return null;
        return (
          <fieldset key={group.key} className="attr-group">
            <legend className="section-label">{pickLabel(group.labels, group.key)}</legend>
            <div className={group.columns === 1 ? "attr-form cols-1" : "attr-form cols-2"}>{defs.map(field)}</div>
          </fieldset>
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
