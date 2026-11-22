import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowDown, ArrowUp, Plus, Trash2, X } from "lucide-react";
import { useEffect, useState } from "react";
import { Link } from "react-router";
import { api, unwrap, type Schemas } from "../api/client";
import { findPrincipalByUsername, useContextsOfKind, useLifecycles, useTypes } from "../api/queries";
import { pickLabel } from "../components/AttributeForm";
import { Dialog, ErrorBanner, Loading, formatDate, stateLabel, typeLabel } from "../components/common";
import { t, tn } from "../i18n/i18n";

export type Definition = Schemas["QueryDefinition"];
type Settings = Schemas["ReportSettings"];
type Report = Schemas["Report"];
type Condition = Schemas["Condition"];
type Column = Schemas["QueryColumn"];
type Link_ = Schemas["QueryLink"];
type FieldInfo = Schemas["FieldInfo"];
type RelationInfo = Schemas["RelationInfo"];
type FieldType = FieldInfo["type"];
type Operator = NonNullable<Condition["operator"]>;
type Aggregate = NonNullable<Column["aggregate"]>;
export type QueryResult = Schemas["QueryResult"];

const PART = "00000000-0000-7000-8000-000000000102";

// ---- vocabulary ----

const OPERATOR_LABELS = (type?: FieldType): Record<Operator, string> => ({
  EQ: t("is"),
  NE: t("is not"),
  LT: type === "DATE" ? t("before") : t("less than"),
  LE: type === "DATE" ? t("on or before") : t("at most"),
  GT: type === "DATE" ? t("after") : t("more than"),
  GE: type === "DATE" ? t("on or after") : t("at least"),
  BETWEEN: t("between"),
  CONTAINS: t("contains"),
  STARTS_WITH: t("starts with"),
  IN: t("is one of"),
  EMPTY: t("is empty"),
  NOT_EMPTY: t("is not empty"),
  LAST_DAYS: t("in the last … days"),
  IS_TRUE: t("is yes"),
  IS_FALSE: t("is no"),
});

/** The same as the engine's: which operators suit which fields. */
const OPERATORS: Record<FieldType, Operator[]> = {
  TEXT: ["EQ", "NE", "CONTAINS", "STARTS_WITH", "IN", "EMPTY", "NOT_EMPTY"],
  NUMBER: ["EQ", "NE", "LT", "LE", "GT", "GE", "BETWEEN", "EMPTY", "NOT_EMPTY"],
  DATE: ["EQ", "LT", "LE", "GT", "GE", "BETWEEN", "LAST_DAYS", "EMPTY", "NOT_EMPTY"],
  BOOLEAN: ["IS_TRUE", "IS_FALSE", "EMPTY", "NOT_EMPTY"],
  ENUM: ["EQ", "NE", "IN", "EMPTY", "NOT_EMPTY"],
  TYPE: ["EQ", "NE", "IN"],
  CONTEXT: ["EQ", "NE", "IN"],
  PRINCIPAL: ["EQ", "NE", "IN", "EMPTY", "NOT_EMPTY"],
  STATE: ["EQ", "NE", "IN"],
};
const NO_VALUE: Operator[] = ["EMPTY", "NOT_EMPTY", "IS_TRUE", "IS_FALSE"];

const AGGREGATES = (): [Aggregate, string, FieldType[] | null][] => [
  ["COUNT", t("Count"), null],
  ["COUNT_DISTINCT", t("Count different"), null],
  ["SUM", t("Total"), ["NUMBER"]],
  ["AVG", t("Average"), ["NUMBER"]],
  ["MIN", t("Lowest"), ["NUMBER", "DATE", "TEXT"]],
  ["MAX", t("Highest"), ["NUMBER", "DATE", "TEXT"]],
];

const GROUPS = (): [NonNullable<Condition["group"]>, string][] => [
  ["AND", t("All of these")],
  ["OR", t("Any of these")],
  ["NOT", t("None of these")],
];

export const BLANK_DEFINITION: Definition = {
  from: { alias: "part", typeId: PART },
  links: [],
  where: { group: "AND", conditions: [] },
  columns: [
    { field: { alias: "part", field: "number" } },
    { field: { alias: "part", field: "name" } },
    { field: { alias: "part", field: "version" } },
    { field: { alias: "part", field: "state" } },
  ],
  sort: [{ column: 0, descending: false }],
  versions: { mode: "LATEST" },
  parameters: [],
};

// ---- the vocabulary from the server ----

export function useRelations() {
  return useQuery({ queryKey: ["queryRelations"], queryFn: () => unwrap(api.GET("/api/v1/query/relations")), staleTime: 300_000 });
}

/** Fields of every named object in [d], by alias: the object's own, and its link's. */
export function useFieldsByAlias(d: Definition): Map<string, FieldInfo[]> {
  const { data: relations } = useRelations();
  const nodes = [
    { alias: d.from.alias, typeId: d.from.typeId ?? null },
    ...d.links.map((l) => ({ alias: l.alias, typeId: linkType(l, relations) })),
  ];
  const results = useQueries({
    queries: nodes.map((n) => ({
      queryKey: ["queryFields", n.typeId ?? "any"],
      queryFn: () => unwrap(api.GET("/api/v1/query/fields", { params: { query: { typeId: n.typeId ?? undefined } } })),
      staleTime: 300_000,
    })),
  });
  const out = new Map<string, FieldInfo[]>();
  nodes.forEach((n, k) => {
    const own = (results[k]?.data ?? []).map((f) => ({
      ...f,
      label: t(f.label),
      values: f.values.map((v) => ({ ...v, label: t(v.label) })),
    }));
    const link = d.links.find((l) => l.alias === n.alias);
    const rel = link && relations?.find((r) => r.key === link.relation);
    const linkFields = (rel?.fields ?? []).map((f) => ({
      ...f,
      key: `link:${f.key}`,
      label: `${t(rel!.label)}: ${t(f.label)}`,
      values: f.values.map((v) => ({ ...v, label: t(v.label) })),
    }));
    out.set(n.alias, [...own, ...linkFields]);
  });
  return out;
}

/** A link's object type: chosen, or the one kind the link leads to (as the engine does). */
function linkType(l: Link_, relations?: RelationInfo[]): string | null {
  if (l.typeId) return l.typeId;
  const to = relations?.find((r) => r.key === l.relation)?.to ?? [];
  if (to.length === 1)
    return { PART: PART, DOCUMENT: "00000000-0000-7000-8000-000000000101", CHANGE: "00000000-0000-7000-8000-000000000103" }[to[0]!] ?? null;
  return null;
}

// ---- describing a query in words ----

export function describeDefinition(d: Definition, types?: Schemas["TypeResponse"][], relations?: RelationInfo[]): string {
  const typeName = (id?: string | null) => (id ? typeLabel(types?.find((x) => x.id === id)) : t("objects"));
  const parts = [typeName(d.from.typeId)];
  for (const l of d.links)
    parts.push(`→ ${relations?.find((r) => r.key === l.relation)?.label ?? l.relation}${l.optional ? ` (${t("optional")})` : ""}`);
  const n = countConditions(d.where);
  if (n) parts.push(tn("{n} condition", "{n} conditions", n));
  if (d.parameters.length) parts.push(tn("{n} question", "{n} questions", d.parameters.length));
  if (d.columns.some((c) => c.aggregate)) parts.push(t("with totals"));
  return parts.join(" ");
}

function countConditions(c?: Condition | null): number {
  if (!c) return 0;
  if (c.group) return (c.conditions ?? []).reduce((n, x) => n + countConditions(x), 0);
  return 1;
}

// ---- showing results ----

function useStateName() {
  const { data: lifecycles } = useLifecycles();
  return (k: string) => {
    for (const lc of lifecycles ?? []) {
      const s = lc.states.find((x) => x.key === k);
      if (s) return pickLabel(s.labels, k);
    }
    return stateLabel(k);
  };
}

export function useCellFormatter() {
  const stateName = useStateName();
  return (col: Schemas["ResultColumn"], v: unknown): string => {
    if (v === null || v === undefined || v === "") return "";
    if (col.type === "DATE" && !col.aggregate?.startsWith("COUNT")) return formatDate(String(v));
    if (col.type === "STATE") return stateName(String(v));
    if (col.type === "BOOLEAN") return v ? t("Yes") : t("No");
    if (typeof v === "number") return `${v.toLocaleString()}${col.unit ? ` ${col.unit}` : ""}`;
    return String(v);
  };
}

/** A query's result: columns as asked; numbers and names open their objects. */
export function QueryTable({ result, label, limit }: { result: QueryResult; label: string; limit?: number }) {
  const show = useCellFormatter();
  const rows = limit ? result.rows.slice(0, limit) : result.rows;
  if (!result.rows.length) return <p className="empty">{t("Nothing found.")}</p>;
  return (
    <div className="table-scroll">
      <table className="grid" aria-label={label}>
        <thead>
          <tr>
            {result.columns.map((c, k) => (
              <th key={k} className={c.type === "NUMBER" ? "num" : undefined}>
                {c.label}
                {c.aggregate && <span className="muted small"> · {AGGREGATES().find((a) => a[0] === c.aggregate)?.[1]}</span>}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={i}>
              {result.columns.map((c, k) => {
                const text = show(c, r.values[k]);
                const id = r.objects[c.alias];
                return (
                  <td key={k} className={c.type === "NUMBER" ? "num" : undefined}>
                    {id && (c.field === "number" || c.field === "name") && text ? <Link to={`/objects/${id}`}>{text}</Link> : text}
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export async function exportQuery(name: string, result: QueryResult, show: ReturnType<typeof useCellFormatter>) {
  const rows = result.rows.map((r) =>
    result.columns.map((c, k) => (typeof r.values[k] === "number" ? (r.values[k] as number) : show(c, r.values[k]))),
  );
  const columns = result.columns.map((c) => (c.unit ? `${c.label} (${c.unit})` : c.label));
  const { data, error } = await api.POST("/api/v1/export/xlsx", {
    body: { sheetName: name.slice(0, 31), columns, rows },
    parseAs: "blob",
    headers: { Accept: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" },
  });
  if (error || !data) throw new Error(t("The export failed"));
  const url = URL.createObjectURL(data as Blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = `${name}.xlsx`;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

/** The questions a report asks before it runs. */
export function AnswersForm({
  definition,
  answers,
  onRun,
}: {
  definition: Definition;
  answers: Record<string, unknown>;
  onRun: (answers: Record<string, unknown>) => void;
}) {
  const [values, setValues] = useState<Record<string, string>>(
    Object.fromEntries(definition.parameters.map((p) => [p.name, String(answers[p.name] ?? p.defaultValue ?? "")])),
  );
  return (
    <form
      className="answers row wrap"
      onSubmit={(e) => {
        e.preventDefault();
        onRun(values);
      }}
    >
      {definition.parameters.map((p) => (
        <label key={p.name} className="field inline">
          <span>
            {p.label}
            {p.required ? " *" : ""}
          </span>
          <input
            type={p.type === "DATE" ? "date" : p.type === "NUMBER" ? "number" : "text"}
            value={values[p.name] ?? ""}
            onChange={(e) => setValues({ ...values, [p.name]: e.target.value })}
          />
        </label>
      ))}
      <button className="btn small primary" type="submit">
        {t("Run with these answers")}
      </button>
    </form>
  );
}

// ---- from a simple report to the builder ----

const SIMPLE_COLUMNS: Record<string, string> = {
  number: "number",
  name: "name",
  type: "type",
  version: "version",
  state: "state",
  context: "context",
  checkedOutBy: "checkedOutBy",
  owner: "owner",
  modified: "modified",
  created: "created",
};

/** A simple report's search as a builder query (all but "within the structure of"). */
export function fromSimple(s: Settings): Definition {
  const q = s.query;
  const typeId = q.typeIds.length === 1 ? q.typeIds[0] : null;
  const alias = "o";
  const conditions: Condition[] = [];
  if (q.text) {
    conditions.push({
      group: "OR",
      conditions: [
        { field: { alias, field: "number" }, operator: "CONTAINS", value: q.text, conditions: [] },
        { field: { alias, field: "name" }, operator: "CONTAINS", value: q.text, conditions: [] },
      ],
    });
  }
  if (q.typeIds.length > 1) conditions.push({ field: { alias, field: "type" }, operator: "IN", value: q.typeIds, conditions: [] });
  if (q.states.length) conditions.push({ field: { alias, field: "state" }, operator: "IN", value: q.states, conditions: [] });
  if (q.contextIds.length) conditions.push({ field: { alias, field: "context" }, operator: "IN", value: q.contextIds, conditions: [] });
  for (const [k, v] of Object.entries(q.attributes))
    conditions.push({ field: { alias, field: `attr:${k}` }, operator: "CONTAINS", value: v, conditions: [] });
  const columns = s.columns
    .map((c) => (c.startsWith("attr:") ? c : SIMPLE_COLUMNS[c]))
    .filter((c): c is string => !!c)
    .map((field) => ({ field: { alias, field } }));
  return {
    ...BLANK_DEFINITION,
    from: { alias, typeId },
    where: { group: "AND", conditions },
    columns: columns.length ? columns : BLANK_DEFINITION.columns.map((c) => ({ field: { alias, field: c.field.field } })),
    sort: [{ column: 0, descending: false }],
  };
}

// ---- the builder ----

const ALIAS_RE = /^[a-z][a-z0-9_]{0,30}$/;

function aliasFor(name: string, taken: string[]): string {
  const base =
    name
      .toLowerCase()
      .normalize("NFD")
      .replace(/[^a-z0-9]+/g, "_")
      .replace(/^_+|_+$/g, "")
      .replace(/^([0-9])/, "x$1")
      .slice(0, 20) || "o";
  let a = base;
  for (let k = 2; taken.includes(a); k++) a = `${base}${k}`;
  return a;
}

function useDebounced<T>(value: T, ms: number): T {
  const [v, setV] = useState(value);
  useEffect(() => {
    const id = setTimeout(() => setV(value), ms);
    return () => clearTimeout(id);
  }, [value, ms]);
  return v;
}

/** The fields offered for conditions and columns: "Alias · Field", by object. */
function FieldSelect({
  value,
  fields,
  onChange,
  label,
}: {
  value: string;
  fields: Map<string, FieldInfo[]>;
  onChange: (alias: string, field: FieldInfo) => void;
  label: string;
}) {
  return (
    <select
      aria-label={label}
      value={value}
      onChange={(e) => {
        const [alias, ...rest] = e.target.value.split("|");
        const key = rest.join("|");
        const f = fields.get(alias!)?.find((x) => x.key === key);
        if (alias && f) onChange(alias, f);
      }}
    >
      {!value && <option value="">{t("Choose a field…")}</option>}
      {[...fields.entries()].map(([alias, list]) => (
        <optgroup key={alias} label={alias}>
          {list.map((f) => (
            <option key={f.key} value={`${alias}|${f.key}`}>
              {f.label}
              {f.unit ? ` (${f.unit})` : ""}
            </option>
          ))}
        </optgroup>
      ))}
    </select>
  );
}

function fieldOf(fields: Map<string, FieldInfo[]>, ref?: { alias: string; field: string } | null): FieldInfo | undefined {
  return ref ? fields.get(ref.alias)?.find((f) => f.key === ref.field) : undefined;
}

/** The choices of a field with a fixed set of values. */
function useChoices(field?: FieldInfo): [string, string][] | null {
  const { data: types } = useTypes();
  const { data: lifecycles } = useLifecycles();
  const products = useContextsOfKind(field?.type === "CONTEXT" ? "PRODUCT" : undefined);
  const libraries = useContextsOfKind(field?.type === "CONTEXT" ? "LIBRARY" : undefined);
  const projects = useContextsOfKind(field?.type === "CONTEXT" ? "PROJECT" : undefined);
  if (!field) return null;
  switch (field.type) {
    case "ENUM":
      return field.values.map((v) => [v.key, v.label]);
    case "TYPE":
      return (types ?? []).map((x) => [x.id, typeLabel(x)]);
    case "STATE": {
      const m = new Map<string, string>();
      for (const lc of lifecycles ?? []) for (const s of lc.states) if (!m.has(s.key)) m.set(s.key, pickLabel(s.labels, s.key));
      return [...m.entries()];
    }
    case "CONTEXT":
      return [...(products.data ?? []), ...(libraries.data ?? []), ...(projects.data ?? [])].map((c) => [c.id, c.name]);
    default:
      return null;
  }
}

function ValueInput({
  field,
  operator,
  value,
  onChange,
}: {
  field: FieldInfo;
  operator: Operator;
  value: unknown;
  onChange: (v: unknown) => void;
}) {
  const choices = useChoices(field);
  const [person, setPerson] = useState("");
  if (NO_VALUE.includes(operator)) return null;
  const input = (v: unknown, set: (x: string) => void, aria: string) => (
    <input
      aria-label={aria}
      type={operator === "LAST_DAYS" || field.type === "NUMBER" ? "number" : field.type === "DATE" ? "date" : "text"}
      value={v === undefined || v === null ? "" : String(v)}
      onChange={(e) => set(e.target.value)}
    />
  );
  if (operator === "BETWEEN") {
    const pair = Array.isArray(value) ? value : ["", ""];
    return (
      <span className="row" style={{ gap: 4 }}>
        {input(pair[0], (x) => onChange([x, pair[1]]), t("From"))}
        <span className="muted small">{t("and")}</span>
        {input(pair[1], (x) => onChange([pair[0], x]), t("To"))}
      </span>
    );
  }
  if (choices && operator !== "LAST_DAYS") {
    if (operator === "IN") {
      const list = Array.isArray(value) ? (value as string[]) : [];
      return (
        <select
          multiple
          aria-label={t("Values")}
          className="multi"
          value={list}
          onChange={(e) => onChange([...e.target.selectedOptions].map((o) => o.value))}
        >
          {choices.map(([k, l]) => (
            <option key={k} value={k}>
              {l}
            </option>
          ))}
        </select>
      );
    }
    return (
      <select aria-label={t("Value")} value={String(value ?? "")} onChange={(e) => onChange(e.target.value)}>
        <option value="">{t("Choose…")}</option>
        {choices.map(([k, l]) => (
          <option key={k} value={k}>
            {l}
          </option>
        ))}
      </select>
    );
  }
  if (field.type === "PRINCIPAL") {
    // Typed as a username; kept as the person's id.
    return (
      <span className="row" style={{ gap: 4 }}>
        <input
          aria-label={t("Username")}
          placeholder={t("Username")}
          value={person || (value ? String(value) : "")}
          onChange={(e) => setPerson(e.target.value)}
          onBlur={async () => {
            if (!person) return;
            const p = await findPrincipalByUsername(person.trim()).catch(() => null);
            onChange(operator === "IN" ? [p?.id ?? person] : (p?.id ?? person));
            setPerson(p?.displayName ?? person);
          }}
        />
      </span>
    );
  }
  return input(
    Array.isArray(value) ? value.join(", ") : value,
    (x) => onChange(operator === "IN" ? x.split(",").map((y) => y.trim()) : x),
    t("Value"),
  );
}

function ConditionRow({
  c,
  fields,
  onChange,
  onRemove,
  askLabel,
  onAskLabel,
}: {
  c: Condition;
  fields: Map<string, FieldInfo[]>;
  onChange: (c: Condition) => void;
  onRemove: () => void;
  askLabel: string;
  onAskLabel: (label: string) => void;
}) {
  const field = fieldOf(fields, c.field);
  const ops = field ? OPERATORS[field.type] : [];
  return (
    <div className="cond-row">
      <FieldSelect
        label={t("Field")}
        value={c.field ? `${c.field.alias}|${c.field.field}` : ""}
        fields={fields}
        onChange={(alias, f) => onChange({ field: { alias, field: f.key }, operator: OPERATORS[f.type][0], value: null, conditions: [] })}
      />
      {field && (
        <select
          aria-label={t("Operator")}
          value={c.operator ?? ""}
          onChange={(e) => onChange({ ...c, operator: e.target.value as Operator, value: null })}
        >
          {ops.map((o) => (
            <option key={o} value={o}>
              {OPERATOR_LABELS(field.type)[o]}
            </option>
          ))}
        </select>
      )}
      {field && c.operator && !c.parameter && (
        <ValueInput field={field} operator={c.operator} value={c.value} onChange={(value) => onChange({ ...c, value })} />
      )}
      {field && c.operator && !NO_VALUE.includes(c.operator) && (
        <label className="check small">
          <input
            type="checkbox"
            checked={!!c.parameter}
            onChange={(e) =>
              onChange({ ...c, parameter: e.target.checked ? `q${Math.random().toString(36).slice(2, 7)}` : null, value: null })
            }
          />
          {t("Ask when run")}
        </label>
      )}
      {c.parameter && (
        <input
          aria-label={t("Question")}
          className="grow"
          placeholder={t("The question")}
          value={askLabel}
          onChange={(e) => onAskLabel(e.target.value)}
        />
      )}
      <button type="button" className="icon-btn" aria-label={t("Remove the condition")} onClick={onRemove}>
        <X size={13} />
      </button>
    </div>
  );
}

function GroupEditor({
  group,
  fields,
  onChange,
  onRemove,
  depth,
  questions,
  setQuestion,
}: {
  group: Condition;
  fields: Map<string, FieldInfo[]>;
  onChange: (c: Condition) => void;
  onRemove?: () => void;
  depth: number;
  questions: Record<string, string>;
  setQuestion: (name: string, label: string) => void;
}) {
  const list = group.conditions ?? [];
  const set = (k: number, c: Condition) => onChange({ ...group, conditions: list.map((x, i) => (i === k ? c : x)) });
  const remove = (k: number) => onChange({ ...group, conditions: list.filter((_, i) => i !== k) });
  return (
    <div className={`cond-group depth-${depth}`}>
      <div className="row" style={{ gap: 6 }}>
        <select
          aria-label={t("Match")}
          value={group.group ?? "AND"}
          onChange={(e) => onChange({ ...group, group: e.target.value as Condition["group"] })}
        >
          {GROUPS().map(([k, l]) => (
            <option key={k} value={k}>
              {l}
            </option>
          ))}
        </select>
        <span className="grow" />
        <button
          type="button"
          className="btn small"
          onClick={() => onChange({ ...group, conditions: [...list, { field: null, operator: null, conditions: [] }] })}
        >
          <Plus size={12} aria-hidden /> {t("Condition")}
        </button>
        {depth < 3 && (
          <button
            type="button"
            className="btn small"
            onClick={() => onChange({ ...group, conditions: [...list, { group: "OR", conditions: [] }] })}
          >
            <Plus size={12} aria-hidden /> {t("Group")}
          </button>
        )}
        {onRemove && (
          <button type="button" className="icon-btn" aria-label={t("Remove the group")} onClick={onRemove}>
            <X size={13} />
          </button>
        )}
      </div>
      {!list.length && (
        <p className="muted small">{depth ? t("An empty group: add conditions to it.") : t("No conditions: everything you can read.")}</p>
      )}
      {list.map((c, k) =>
        c.group ? (
          <GroupEditor
            key={k}
            group={c}
            fields={fields}
            depth={depth + 1}
            onChange={(x) => set(k, x)}
            onRemove={() => remove(k)}
            questions={questions}
            setQuestion={setQuestion}
          />
        ) : (
          <ConditionRow
            key={k}
            c={c}
            fields={fields}
            onChange={(x) => set(k, x)}
            onRemove={() => remove(k)}
            askLabel={c.parameter ? (questions[c.parameter] ?? "") : ""}
            onAskLabel={(label) => c.parameter && setQuestion(c.parameter, label)}
          />
        ),
      )}
    </div>
  );
}

/** Conditions that ask, and the questions they need. */
function parametersOf(
  where: Condition | null | undefined,
  labels: Record<string, string>,
  fields: Map<string, FieldInfo[]>,
): Definition["parameters"] {
  const out: Definition["parameters"] = [];
  const walk = (c?: Condition | null) => {
    if (!c) return;
    if (c.group) (c.conditions ?? []).forEach(walk);
    else if (c.parameter) {
      const f = fieldOf(fields, c.field);
      const type = f?.type === "NUMBER" || f?.type === "DATE" ? f.type : "TEXT";
      out.push({ name: c.parameter, label: labels[c.parameter] || f?.label || c.parameter, type, required: false });
    }
  };
  walk(where);
  return out;
}

export function ReportBuilder({
  report,
  start,
  onClose,
  onSaved,
  scheduleFields,
  resolveRecipients,
}: {
  report: Report | null;
  start?: Definition;
  onClose: () => void;
  onSaved: (r: Report) => void;
  scheduleFields: (s: Settings, setS: (s: Settings) => void, recipients: string, setRecipients: (r: string) => void) => React.ReactNode;
  resolveRecipients: (text: string) => Promise<string[]>;
}) {
  const qc = useQueryClient();
  const { data: types } = useTypes();
  const { data: relations } = useRelations();
  const initial: Settings = report?.settings ?? {
    name: "",
    description: "",
    query: { text: "", typeIds: [], states: [], contextIds: [], attributes: {}, offset: 0, limit: 25 },
    columns: ["number"],
    groupBy: null,
    shared: false,
    cron: null,
    timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone ?? "UTC",
    recipients: [],
    alert: "ALWAYS",
    threshold: null,
    answers: {},
  };
  const [s, setS] = useState<Settings>(initial);
  const [d, setD] = useState<Definition>(report?.settings.definition ?? start ?? BLANK_DEFINITION);
  const [questions, setQuestions] = useState<Record<string, string>>(
    Object.fromEntries((d.parameters ?? []).map((p) => [p.name, p.label])),
  );
  const [recipients, setRecipients] = useState((report?.settings.recipients ?? []).join(", "));
  const fields = useFieldsByAlias(d);
  const aliases = [d.from.alias, ...d.links.map((l) => l.alias)];
  const definition = (): Definition => ({ ...d, parameters: parametersOf(d.where, questions, fields) });

  // A live preview of the first rows, a moment after each change.
  const ready = d.columns.length > 0 && d.columns.every((c) => fieldOf(fields, c.field));
  const debounced = useDebounced(JSON.stringify(definition()), 600);
  const preview = useQuery({
    queryKey: ["queryPreview", debounced],
    queryFn: () =>
      unwrap(
        api.POST("/api/v1/query/run", {
          body: { definition: JSON.parse(debounced) as Definition, parameters: s.answers ?? {}, limit: 50 },
        }),
      ),
    enabled: ready,
    retry: false,
  });

  const save = useMutation({
    mutationFn: async () => {
      const body: Settings = { ...s, definition: definition(), recipients: await resolveRecipients(recipients) };
      return report
        ? unwrap(api.PUT("/api/v1/reports/{id}", { params: { path: { id: report.id } }, body }))
        : unwrap(api.POST("/api/v1/reports", { body }));
    },
    onSuccess: (r) => {
      void qc.invalidateQueries({ queryKey: ["reports"] });
      void qc.invalidateQueries({ queryKey: ["reportRun", r.id] });
      onSaved(r);
    },
  });

  const baseOf = (alias: string) => {
    const typeId = alias === d.from.alias ? d.from.typeId : linkType(d.links.find((l) => l.alias === alias)!, relations);
    return types?.find((x) => x.id === typeId)?.baseClass;
  };
  const relationsFrom = (alias: string) =>
    (relations ?? []).filter((r) => !r.from.length || !baseOf(alias) || r.from.includes(baseOf(alias)!));
  const renameAlias = (from: string, to: string) => setD(renamed(d, from, to));
  const setColumn = (k: number, c: Column) => setD({ ...d, columns: d.columns.map((x, i) => (i === k ? c : x)) });
  const moveColumn = (k: number, by: number) => {
    const cols = [...d.columns];
    const [c] = cols.splice(k, 1);
    cols.splice(k + by, 0, c!);
    setD({ ...d, columns: cols, sort: [] });
  };
  const sort = d.sort[0];

  return (
    <Dialog
      open
      wide
      title={report ? t("Edit {name}", { name: report.settings.name }) : t("New report")}
      onClose={onClose}
      footer={
        <>
          <span className="muted small grow">
            {preview.data ? t("Finds {count} rows now", { count: `${preview.data.rows.length}${preview.data.capped ? "+" : ""}` }) : ""}
          </span>
          <button className="btn" onClick={onClose}>
            {t("Cancel")}
          </button>
          <button className="btn primary" disabled={save.isPending || !s.name.trim() || !ready} onClick={() => save.mutate()}>
            {t("Save")}
          </button>
        </>
      }
    >
      <div className="report-builder">
        <ErrorBanner error={save.error} />
        <div className="row wrap" style={{ gap: 10 }}>
          <label className="field grow">
            <span>{t("Name")}</span>
            <input value={s.name} onChange={(e) => setS({ ...s, name: e.target.value })} autoFocus aria-label={t("Name")} />
          </label>
          <label className="field grow">
            <span>{t("Description")}</span>
            <input
              value={s.description ?? ""}
              onChange={(e) => setS({ ...s, description: e.target.value })}
              aria-label={t("Description")}
            />
          </label>
        </div>

        <section className="builder-section" aria-label={t("Find")}>
          <h4>{t("1. Find")}</h4>
          <div className="row wrap" style={{ gap: 8 }}>
            <select
              aria-label={t("Objects of type")}
              value={d.from.typeId ?? ""}
              onChange={(e) => {
                const typeId = e.target.value || null;
                const alias = typeId ? aliasFor(typeLabel(types?.find((x) => x.id === typeId)), aliases.slice(1)) : "o";
                setD(renamed({ ...d, from: { ...d.from, typeId } }, d.from.alias, alias));
              }}
            >
              <option value="">{t("Any object")}</option>
              {types?.map((x) => (
                <option key={x.id} value={x.id}>
                  {typeLabel(x)}
                </option>
              ))}
            </select>
            <span className="muted small">{t("named")}</span>
            <AliasInput key={d.from.alias} value={d.from.alias} taken={aliases} onChange={(a) => renameAlias(d.from.alias, a)} />
            <select
              aria-label={t("Versions")}
              value={d.versions.mode === "STATE" ? `STATE:${d.versions.state ?? "RELEASED"}` : d.versions.mode}
              onChange={(e) => {
                const v = e.target.value;
                setD({
                  ...d,
                  versions: v.startsWith("STATE:")
                    ? { mode: "STATE", state: v.slice(6) }
                    : { mode: v as "LATEST" | "AS_OF", asOf: d.versions.asOf },
                });
              }}
            >
              <option value="LATEST">{t("Latest versions")}</option>
              <option value="STATE:RELEASED">{t("Latest released versions")}</option>
              <option value="AS_OF">{t("Versions as of a date")}</option>
            </select>
            {d.versions.mode === "AS_OF" && (
              <input
                type="date"
                aria-label={t("As of")}
                value={(d.versions.asOf ?? "").slice(0, 10)}
                onChange={(e) => setD({ ...d, versions: { mode: "AS_OF", asOf: e.target.value ? `${e.target.value}T23:59:59Z` : null } })}
              />
            )}
          </div>
          {d.links.map((l, k) => (
            <div key={k} className="row wrap link-row" style={{ gap: 6 }}>
              <span className="muted small">{t("and from")}</span>
              <select
                aria-label={t("From")}
                value={l.from}
                onChange={(e) => setD({ ...d, links: d.links.map((x, i) => (i === k ? { ...x, from: e.target.value } : x)) })}
              >
                {aliases.slice(0, k + 1).map((a) => (
                  <option key={a} value={a}>
                    {a}
                  </option>
                ))}
              </select>
              <select
                aria-label={t("Follow")}
                value={l.relation}
                onChange={(e) =>
                  setD({ ...d, links: d.links.map((x, i) => (i === k ? { ...x, relation: e.target.value, typeId: null } : x)) })
                }
              >
                {relationsFrom(l.from).map((r) => (
                  <option key={r.key} value={r.key}>
                    {t(r.label)}
                  </option>
                ))}
              </select>
              <select
                aria-label={t("Only of type")}
                value={l.typeId ?? ""}
                onChange={(e) => setD({ ...d, links: d.links.map((x, i) => (i === k ? { ...x, typeId: e.target.value || null } : x)) })}
              >
                <option value="">{t("Any type")}</option>
                {types
                  ?.filter((x) => {
                    const to = relations?.find((r) => r.key === l.relation)?.to ?? [];
                    return !to.length || to.includes(x.baseClass);
                  })
                  .map((x) => (
                    <option key={x.id} value={x.id}>
                      {typeLabel(x)}
                    </option>
                  ))}
              </select>
              <span className="muted small">{t("named")}</span>
              <AliasInput key={l.alias} value={l.alias} taken={aliases} onChange={(a) => renameAlias(l.alias, a)} />
              <label className="check small">
                <input
                  type="checkbox"
                  checked={!!l.optional}
                  onChange={(e) => setD({ ...d, links: d.links.map((x, i) => (i === k ? { ...x, optional: e.target.checked } : x)) })}
                />
                {t("Keep those without")}
              </label>
              <button
                type="button"
                className="icon-btn"
                aria-label={t("Remove the link")}
                disabled={d.links.some((x) => x.from === l.alias)}
                onClick={() => {
                  const keep = (a: string) => a !== l.alias;
                  setD({
                    ...d,
                    links: d.links.filter((_, i) => i !== k),
                    columns: d.columns.filter((c) => keep(c.field.alias)),
                    sort: [],
                  });
                }}
              >
                <Trash2 size={13} />
              </button>
            </div>
          ))}
          <button
            type="button"
            className="btn small"
            disabled={!relations?.length || d.links.length >= 8}
            onClick={() => {
              const from = aliases[aliases.length - 1]!;
              const r = relationsFrom(from)[0] ?? relations![0]!;
              const alias = aliasFor(r.key === "uses" ? "child" : r.key === "usedBy" ? "parent" : r.label.split(" ")[0]!, aliases);
              setD({ ...d, links: [...d.links, { alias, from, relation: r.key, optional: false }] });
            }}
          >
            <Plus size={12} aria-hidden /> {t("Follow a link")}
          </button>
        </section>

        <section className="builder-section" aria-label={t("Conditions")}>
          <h4>{t("2. Where")}</h4>
          <GroupEditor
            group={d.where ?? { group: "AND", conditions: [] }}
            fields={fields}
            depth={0}
            onChange={(where) => setD({ ...d, where })}
            questions={questions}
            setQuestion={(name, label) => setQuestions({ ...questions, [name]: label })}
          />
        </section>

        <section className="builder-section" aria-label={t("Columns")}>
          <h4>{t("3. Show")}</h4>
          <table className="grid compact" aria-label={t("Report columns")}>
            <thead>
              <tr>
                <th>{t("Field")}</th>
                <th>{t("Total")}</th>
                <th>{t("Heading")}</th>
                <th aria-label={t("Actions")} />
              </tr>
            </thead>
            <tbody>
              {d.columns.map((c, k) => {
                const f = fieldOf(fields, c.field);
                return (
                  <tr key={k}>
                    <td>
                      <FieldSelect
                        label={t("Column {n}", { n: k + 1 })}
                        value={`${c.field.alias}|${c.field.field}`}
                        fields={fields}
                        onChange={(alias, x) => setColumn(k, { ...c, field: { alias, field: x.key }, aggregate: null })}
                      />
                    </td>
                    <td>
                      <select
                        aria-label={t("Total of column {n}", { n: k + 1 })}
                        value={c.aggregate ?? ""}
                        onChange={(e) => setColumn(k, { ...c, aggregate: (e.target.value || null) as Aggregate | null })}
                      >
                        <option value="">—</option>
                        {AGGREGATES()
                          .filter(([, , ok]) => !ok || (f && ok.includes(f.type)))
                          .map(([a, l]) => (
                            <option key={a} value={a}>
                              {l}
                            </option>
                          ))}
                      </select>
                    </td>
                    <td>
                      <input
                        aria-label={t("Heading of column {n}", { n: k + 1 })}
                        placeholder={f?.label}
                        value={c.label ?? ""}
                        onChange={(e) => setColumn(k, { ...c, label: e.target.value })}
                      />
                    </td>
                    <td className="nowrap">
                      <button type="button" className="icon-btn" aria-label={t("Move up")} disabled={!k} onClick={() => moveColumn(k, -1)}>
                        <ArrowUp size={13} />
                      </button>
                      <button
                        type="button"
                        className="icon-btn"
                        aria-label={t("Move down")}
                        disabled={k === d.columns.length - 1}
                        onClick={() => moveColumn(k, 1)}
                      >
                        <ArrowDown size={13} />
                      </button>
                      <button
                        type="button"
                        className="icon-btn"
                        aria-label={t("Remove column {n}", { n: k + 1 })}
                        disabled={d.columns.length < 2}
                        onClick={() => setD({ ...d, columns: d.columns.filter((_, i) => i !== k), sort: [] })}
                      >
                        <X size={13} />
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          <div className="row wrap" style={{ gap: 8 }}>
            <button
              type="button"
              className="btn small"
              disabled={d.columns.length >= 60}
              onClick={() => setD({ ...d, columns: [...d.columns, { field: { alias: aliases[aliases.length - 1]!, field: "number" } }] })}
            >
              <Plus size={12} aria-hidden /> {t("Column")}
            </button>
            <span className="grow" />
            <label className="field inline">
              <span>{t("Sort by")}</span>
              <select
                aria-label={t("Sort by")}
                value={sort ? String(sort.column) : ""}
                onChange={(e) =>
                  setD({ ...d, sort: e.target.value === "" ? [] : [{ column: Number(e.target.value), descending: !!sort?.descending }] })
                }
              >
                <option value="">{t("The first column")}</option>
                {d.columns.map((c, k) => (
                  <option key={k} value={k}>
                    {c.label || fieldOf(fields, c.field)?.label || c.field.field}
                  </option>
                ))}
              </select>
            </label>
            <label className="check small">
              <input
                type="checkbox"
                checked={!!sort?.descending}
                disabled={!sort}
                onChange={(e) => sort && setD({ ...d, sort: [{ ...sort, descending: e.target.checked }] })}
              />
              {t("Descending")}
            </label>
            <label className="field inline">
              <span>{t("Chart")}</span>
              <select aria-label={t("Chart")} value={s.groupBy ?? ""} onChange={(e) => setS({ ...s, groupBy: e.target.value || null })}>
                <option value="">{t("No chart")}</option>
                {d.columns.map((c, k) =>
                  c.aggregate ? null : (
                    <option key={k} value={`col:${k}`}>
                      {t("By {column}", { column: c.label || fieldOf(fields, c.field)?.label || c.field.field })}
                    </option>
                  ),
                )}
              </select>
            </label>
          </div>
        </section>

        <section className="builder-section" aria-label={t("Preview")}>
          <h4>
            {t("Preview")} {preview.isFetching && <span className="muted small">{t("running…")}</span>}
          </h4>
          {!ready ? (
            <p className="muted small">{t("Choose a field for every column to see the result.")}</p>
          ) : preview.error ? (
            <ErrorBanner error={preview.error} />
          ) : preview.data ? (
            <QueryTable result={preview.data} label={t("Preview")} limit={20} />
          ) : (
            <Loading />
          )}
        </section>

        <section className="builder-section" aria-label={t("Schedule and sharing")}>
          <h4>{t("4. Share and schedule")}</h4>
          <label className="check">
            <input type="checkbox" checked={s.shared} onChange={(e) => setS({ ...s, shared: e.target.checked })} />
            {t("Share it (others run it with their own access)")}
          </label>
          <div className="attr-form">{scheduleFields(s, setS, recipients, setRecipients)}</div>
        </section>
      </div>
    </Dialog>
  );
}

/** [d] with the object named [from] named [to] everywhere. */
function renamed(d: Definition, from: string, to: string): Definition {
  if (from === to) return d;
  const swap = (a: string) => (a === from ? to : a);
  const walk = (c?: Condition | null): Condition | null | undefined =>
    c && { ...c, field: c.field && { ...c.field, alias: swap(c.field.alias) }, conditions: c.conditions?.map((x) => walk(x)!) };
  return {
    ...d,
    from: { ...d.from, alias: swap(d.from.alias) },
    links: d.links.map((l) => ({ ...l, alias: swap(l.alias), from: swap(l.from) })),
    where: walk(d.where),
    columns: d.columns.map((c) => ({ ...c, field: { ...c.field, alias: swap(c.field.alias) } })),
  };
}

function AliasInput({ value, taken, onChange }: { value: string; taken: string[]; onChange: (a: string) => void }) {
  const [text, setText] = useState(value);
  const ok = ALIAS_RE.test(text) && (text === value || !taken.includes(text));
  return (
    <input
      aria-label={t("Name used in fields")}
      className={`code alias${ok ? "" : " invalid"}`}
      size={10}
      value={text}
      onChange={(e) => setText(e.target.value.toLowerCase())}
      onBlur={() => (ok && text !== value ? onChange(text) : setText(value))}
    />
  );
}
