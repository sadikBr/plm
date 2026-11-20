import { useState } from "react";
import type { Attributes } from "../api/queries";

interface Row {
  key: string;
  value: string;
}

/** Values are edited as text. Numbers, booleans and JSON objects/arrays are parsed back to their type. */
export function parseValue(text: string): unknown {
  const trimmed = text.trim();
  if (trimmed === "") return "";
  if (trimmed === "true") return true;
  if (trimmed === "false") return false;
  if (trimmed === "null") return null;
  if (/^-?\d+(\.\d+)?([eE][-+]?\d+)?$/.test(trimmed)) return Number(trimmed);
  if (/^[[{]/.test(trimmed)) {
    try {
      return JSON.parse(trimmed);
    } catch {
      return text;
    }
  }
  return text;
}

export function formatValue(value: unknown): string {
  if (value === null) return "null";
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

export function toRows(attributes: Attributes): Row[] {
  return Object.entries(attributes).map(([key, value]) => ({ key, value: formatValue(value) }));
}

/** @returns the attributes, or an error message if keys are blank or duplicated. */
export function fromRows(rows: Row[]): { attributes: Attributes } | { error: string } {
  const attributes: Attributes = {};
  for (const row of rows) {
    const key = row.key.trim();
    if (key === "" && row.value.trim() === "") continue;
    if (key === "") return { error: "Every attribute needs a name." };
    if (key in attributes) return { error: `"${key}" appears twice.` };
    attributes[key] = parseValue(row.value);
  }
  return { attributes };
}

/** Read-only attribute list. */
export function AttributesView({ attributes }: { attributes: Attributes }) {
  const entries = Object.entries(attributes);
  if (entries.length === 0) return <p className="muted">No attributes.</p>;
  return (
    <dl className="props">
      {entries.map(([key, value]) => (
        <div key={key} style={{ display: "contents" }}>
          <dt>{key}</dt>
          <dd className={typeof value === "object" && value !== null ? "mono" : undefined}>{formatValue(value)}</dd>
        </div>
      ))}
    </dl>
  );
}

type Parsed = { attributes: Attributes } | { error: string };

/**
 * Editable name/value list. With [onSave] it shows a save button. With [onChange] it reports
 * every edit (for use inside a larger form).
 */
export function AttributesEditor({
  initial,
  onSave,
  onChange,
  saving,
  saveLabel = "Save attributes",
}: {
  initial: Attributes;
  onSave?: (attributes: Attributes) => void;
  onChange?: (result: Parsed) => void;
  saving?: boolean;
  saveLabel?: string;
}) {
  const [rows, setRows] = useState<Row[]>(() => [...toRows(initial), { key: "", value: "" }]);
  const [error, setError] = useState<string>();

  const apply = (next: Row[]) => {
    // Always keep one empty row at the end to add to.
    const last = next[next.length - 1];
    if (!last || last.key !== "" || last.value !== "") next = [...next, { key: "", value: "" }];
    setRows(next);
    setError(undefined);
    onChange?.(fromRows(next));
  };

  const update = (index: number, patch: Partial<Row>) =>
    apply(rows.map((row, i) => (i === index ? { ...row, ...patch } : row)));

  const save = () => {
    const result = fromRows(rows);
    if ("error" in result) setError(result.error);
    else onSave?.(result.attributes);
  };

  return (
    <div>
      {rows.map((row, i) => (
        <div className="row" key={i} style={{ marginBottom: 6 }}>
          <input
            aria-label={`Attribute ${i + 1} name`}
            placeholder="name"
            value={row.key}
            onChange={(e) => update(i, { key: e.target.value })}
            style={{ flex: "0 0 38%" }}
          />
          <input
            aria-label={`Attribute ${i + 1} value`}
            placeholder="value"
            value={row.value}
            onChange={(e) => update(i, { value: e.target.value })}
          />
          <button
            type="button"
            className="btn small"
            aria-label={`Remove attribute ${i + 1}`}
            disabled={i === rows.length - 1}
            onClick={() => apply(rows.filter((_, j) => j !== i))}
          >
            ✕
          </button>
        </div>
      ))}
      {error && (
        <div className="banner error" role="alert">
          {error}
        </div>
      )}
      <p className="hint muted" style={{ fontSize: 12.5 }}>
        Numbers, <code>true</code>/<code>false</code> and JSON like <code>{'{"value":1,"unit":"kg"}'}</code> keep their type.
      </p>
      {onSave && (
        <button type="button" className="btn primary" onClick={save} disabled={saving}>
          {saveLabel}
        </button>
      )}
    </div>
  );
}
