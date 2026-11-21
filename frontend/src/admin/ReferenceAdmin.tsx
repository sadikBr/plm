import { ArrowDown, ArrowUp, Pencil, Plus, Save } from "lucide-react";
import { useMemo, useState, type FormEvent } from "react";
import { useTeamRoles, type TeamRoleDefinition } from "../api/queries";
import { pickLabel } from "../components/AttributeForm";
import { ErrorBanner, Loading, Panel } from "../components/common";
import { t } from "../i18n/i18n";
import {
  useCreateEnumeration,
  useCreateNumberingScheme,
  useCreateRole,
  useCreateUnit,
  useEnumerationList,
  useNumberingSchemes,
  useUnitList,
  useUpdateEnumeration,
  useUpdateRole,
} from "./adminQueries";

/** Field-level problems from an admin validation error, or the plain message. */
function Problems({ error }: { error: unknown }) {
  const errors = (error as { problem?: { details?: { errors?: unknown } } } | null)?.problem?.details?.errors;
  if (Array.isArray(errors) && errors.every((e) => typeof e === "string")) {
    return (
      <div className="banner error" role="alert">
        <ul style={{ margin: 0, paddingLeft: 16 }}>
          {(errors as string[]).map((e) => (
            <li key={e}>{e}</li>
          ))}
        </ul>
      </div>
    );
  }
  return <ErrorBanner error={error} />;
}

// ---------- Value lists ----------

interface ValueRow {
  key: string;
  label: string;
  active: boolean;
  /** Existing values keep their key: data refers to it. */
  saved: boolean;
}

export function ValueListsAdmin() {
  const { data: lists, isLoading } = useEnumerationList();
  const [selected, setSelected] = useState<string | "new" | null>(null);
  const current = selected === "new" ? undefined : (lists?.find((l) => l.id === selected) ?? lists?.[0]);

  if (isLoading) return <Loading />;
  return (
    <div className="admin-split">
      <Panel
        title={t("Value lists")}
        flush
        actions={
          <button className="btn small" onClick={() => setSelected("new")}>
            <Plus size={13} aria-hidden />
            {t("New list")}
          </button>
        }
      >
        <ul className="type-tree" aria-label={t("Value lists")}>
          {lists?.map((l) => (
            <li key={l.id}>
              <button
                className={selected !== "new" && current?.id === l.id ? "type-row active" : "type-row"}
                onClick={() => setSelected(l.id)}
              >
                {pickLabel(l.labels, l.name)}
                <span className="muted" style={{ marginLeft: "auto" }}>
                  {l.values.filter((v) => v.active !== false).length}
                </span>
              </button>
            </li>
          ))}
        </ul>
      </Panel>
      <ValueListEditor key={selected === "new" ? "new" : current?.id} list={current} onCreated={(id) => setSelected(id)} />
    </div>
  );
}

function ValueListEditor({
  list,
  onCreated,
}: {
  list?: {
    id: string;
    name: string;
    labels: Record<string, string>;
    system: boolean;
    values: { key: string; labels: Record<string, string>; active?: boolean | null }[];
  };
  onCreated: (id: string) => void;
}) {
  const create = useCreateEnumeration();
  const update = useUpdateEnumeration();
  const mutation = list ? update : create;
  const [name, setName] = useState(list?.name ?? "");
  const [label, setLabel] = useState(list ? pickLabel(list.labels, list.name) : "");
  const [rows, setRows] = useState<ValueRow[]>(
    list?.values.map((v) => ({ key: v.key, label: pickLabel(v.labels, v.key), active: v.active !== false, saved: true })) ?? [],
  );
  const [dirty, setDirty] = useState(false);
  const edit = (next: ValueRow[]) => {
    setRows(next);
    setDirty(true);
  };
  const set = (i: number, patch: Partial<ValueRow>) => edit(rows.map((r, j) => (j === i ? { ...r, ...patch } : r)));
  const move = (i: number, by: number) => {
    const next = [...rows];
    const [r] = next.splice(i, 1);
    next.splice(i + by, 0, r!);
    edit(next);
  };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const original = new Map(list?.values.map((v) => [v.key, v.labels]) ?? []);
    const body = {
      name: list ? undefined : name.trim(),
      labels: { ...(list?.labels ?? {}), en: label.trim() },
      values: rows.map((r) => ({ key: r.key.trim(), labels: { ...(original.get(r.key) ?? {}), en: r.label.trim() }, active: r.active })),
    };
    if (list) update.mutate({ id: list.id, body }, { onSuccess: () => setDirty(false) });
    else create.mutate(body, { onSuccess: (created) => onCreated(created.id) });
  };

  return (
    <form className="panel" aria-label={list ? t("Value list {name}", { name: label }) : t("New value list")} onSubmit={submit} noValidate>
      <div className="panel-head">
        <h2>{list ? pickLabel(list.labels, list.name) : t("New value list")}</h2>
        {list?.system && (
          <span className="note">{t("Out of the box: values can be added, relabelled and deactivated, never removed")}</span>
        )}
      </div>
      <div className="panel-body" style={{ display: "grid", gap: 12 }}>
        <Problems error={mutation.error} />
        <div className="attr-form cols-2">
          <div className="field">
            <label htmlFor="list-label">{t("Label")}</label>
            <input id="list-label" value={label} onChange={(e) => (setLabel(e.target.value), setDirty(true))} required />
          </div>
          <div className="field">
            <label htmlFor="list-name">{t("Name")}</label>
            <input
              id="list-name"
              value={name}
              disabled={!!list}
              onChange={(e) => setName(e.target.value)}
              placeholder={t("e.g. {example}", { example: "threadStandard" })}
            />
          </div>
        </div>
        <table className="grid" aria-label={t("Values")}>
          <thead>
            <tr>
              <th>{t("Key")}</th>
              <th>{t("Label")}</th>
              <th>{t("Active")}</th>
              <th aria-label={t("Order")} />
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={i} className={r.active ? undefined : "inactive"}>
                <td style={{ width: "32%" }}>
                  {r.saved ? (
                    <code>{r.key}</code>
                  ) : (
                    <input
                      aria-label={t("Key of value {n}", { n: i + 1 })}
                      value={r.key}
                      onChange={(e) => set(i, { key: e.target.value })}
                    />
                  )}
                </td>
                <td>
                  <input
                    aria-label={t("Label of value {n}", { n: i + 1 })}
                    value={r.label}
                    onChange={(e) => set(i, { label: e.target.value })}
                  />
                </td>
                <td style={{ width: 70 }}>
                  {r.saved ? (
                    <input
                      type="checkbox"
                      aria-label={t("{value} is active", { value: r.label })}
                      checked={r.active}
                      onChange={(e) => set(i, { active: e.target.checked })}
                      style={{ width: "auto" }}
                    />
                  ) : (
                    <button type="button" className="btn small ghost" onClick={() => edit(rows.filter((_, j) => j !== i))}>
                      {t("Remove")}
                    </button>
                  )}
                </td>
                <td className="nowrap" style={{ width: 70 }}>
                  <button
                    type="button"
                    className="btn small ghost icon"
                    aria-label={t("Move {value} up", { value: r.label || t("value") })}
                    disabled={i === 0}
                    onClick={() => move(i, -1)}
                  >
                    <ArrowUp size={12} aria-hidden />
                  </button>
                  <button
                    type="button"
                    className="btn small ghost icon"
                    aria-label={t("Move {value} down", { value: r.label || t("value") })}
                    disabled={i === rows.length - 1}
                    onClick={() => move(i, 1)}
                  >
                    <ArrowDown size={12} aria-hidden />
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <div>
          <button type="button" className="btn small" onClick={() => edit([...rows, { key: "", label: "", active: true, saved: false }])}>
            <Plus size={13} aria-hidden />
            {t("Add value")}
          </button>
          <span className="hint" style={{ marginLeft: 8 }}>
            {t("Saved values can't be removed, only deactivated, so existing data stays valid.")}
          </span>
        </div>
      </div>
      <div className="panel-foot">
        <button className="btn primary" type="submit" disabled={mutation.isPending || !label.trim() || (!!list && !dirty)}>
          <Save size={14} aria-hidden />
          {list ? t("Save list") : t("Create list")}
        </button>
      </div>
    </form>
  );
}

// ---------- Units ----------

export function UnitsAdmin() {
  const { data: units, isLoading } = useUnitList();
  const groups = useMemo(() => {
    const by = new Map<string, NonNullable<typeof units>>();
    for (const u of units ?? []) by.set(u.dimension, [...(by.get(u.dimension) ?? []), u]);
    return [...by.entries()].sort(([a], [b]) => a.localeCompare(b));
  }, [units]);
  if (isLoading) return <Loading />;
  return (
    <div className="cols">
      <Panel title={t("Units of measure")} note={t("Values are stored with their unit and compared in the base unit (factor 1)")} flush>
        <div className="grid-wrap">
          <table className="grid" aria-label={t("Units")}>
            <thead>
              <tr>
                <th>{t("Measures")}</th>
                <th>{t("Unit")}</th>
                <th>{t("Name")}</th>
                <th>{t("In base unit")}</th>
              </tr>
            </thead>
            <tbody>
              {groups.flatMap(([dimension, list]) =>
                list.map((u, i) => (
                  <tr key={u.code}>
                    <td className="nowrap">{i === 0 ? dimension.charAt(0) + dimension.slice(1).toLowerCase().replace(/_/g, " ") : ""}</td>
                    <td className="nowrap">
                      <code>{u.code}</code>
                    </td>
                    <td>{pickLabel(u.labels, u.code)}</td>
                    <td className="nowrap muted">
                      {Number(u.factor) === 1 && Number(u.offset) === 0
                        ? t("Base unit")
                        : `× ${Number(u.factor)}${Number(u.offset) ? ` + ${Number(u.offset)}` : ""}`}
                      {!u.system && (
                        <span className="tag" style={{ marginLeft: 6 }}>
                          {t("Added")}
                        </span>
                      )}
                    </td>
                  </tr>
                )),
              )}
            </tbody>
          </table>
        </div>
      </Panel>
      <NewUnitPanel
        dimensions={groups.map(([d, list]) => [d, list.find((u) => Number(u.factor) === 1 && Number(u.offset) === 0)?.code ?? ""])}
      />
    </div>
  );
}

function NewUnitPanel({ dimensions }: { dimensions: [string, string][] }) {
  const create = useCreateUnit();
  const [code, setCode] = useState("");
  const [label, setLabel] = useState("");
  const [dimension, setDimension] = useState(dimensions[0]?.[0] ?? "");
  const [newDimension, setNewDimension] = useState("");
  const [factor, setFactor] = useState("1");
  const [offset, setOffset] = useState("0");
  const isNew = dimension === "__new";
  const base = dimensions.find(([d]) => d === dimension)?.[1];

  const submit = (e: FormEvent) => {
    e.preventDefault();
    create.mutate(
      {
        code: code.trim(),
        dimension: isNew ? newDimension.trim().toUpperCase() : dimension,
        labels: { en: label.trim() },
        factor: isNew ? 1 : Number(factor),
        offset: isNew ? 0 : Number(offset),
      },
      {
        onSuccess: () => {
          setCode("");
          setLabel("");
        },
      },
    );
  };

  return (
    <form className="panel" aria-labelledby="new-unit-title" onSubmit={submit} noValidate>
      <div className="panel-head">
        <h2 id="new-unit-title">{t("Add a unit")}</h2>
      </div>
      <div className="panel-body" style={{ display: "grid", gap: 10 }}>
        <Problems error={create.error} />
        <div className="attr-form cols-2">
          <div className="field">
            <label htmlFor="unit-code">{t("Symbol")}</label>
            <input
              id="unit-code"
              value={code}
              onChange={(e) => setCode(e.target.value)}
              placeholder={t("e.g. {example}", { example: "kN·m" })}
            />
          </div>
          <div className="field">
            <label htmlFor="unit-label">{t("Name")}</label>
            <input id="unit-label" value={label} onChange={(e) => setLabel(e.target.value)} placeholder={t("kilonewton metre")} />
          </div>
        </div>
        <div className="field">
          <label htmlFor="unit-dimension">{t("Measures")}</label>
          <select id="unit-dimension" value={dimension} onChange={(e) => setDimension(e.target.value)}>
            {dimensions.map(([d]) => (
              <option key={d} value={d}>
                {d.charAt(0) + d.slice(1).toLowerCase().replace(/_/g, " ")}
              </option>
            ))}
            <option value="__new">{t("A new dimension…")}</option>
          </select>
        </div>
        {isNew ? (
          <div className="field">
            <label htmlFor="unit-new-dimension">{t("New dimension")}</label>
            <input id="unit-new-dimension" value={newDimension} onChange={(e) => setNewDimension(e.target.value)} placeholder="TORQUE" />
            <span className="hint">{t("This unit becomes its base unit; add others relative to it.")}</span>
          </div>
        ) : (
          <div className="attr-form cols-2">
            <div className="field">
              <label htmlFor="unit-factor">{t("1 {unit} = … {base}", { unit: code || t("unit"), base: base ?? "" })}</label>
              <input id="unit-factor" inputMode="decimal" value={factor} onChange={(e) => setFactor(e.target.value)} />
            </div>
            <div className="field">
              <label htmlFor="unit-offset">{t("Plus (offset)")}</label>
              <input id="unit-offset" inputMode="decimal" value={offset} onChange={(e) => setOffset(e.target.value)} />
            </div>
          </div>
        )}
        <p className="muted" style={{ margin: 0 }}>
          {t("Units can't be changed or removed later: stored values may use them.")}
        </p>
      </div>
      <div className="panel-foot">
        <button
          className="btn primary"
          type="submit"
          disabled={create.isPending || !code.trim() || !label.trim() || (isNew && !newDimension.trim())}
        >
          <Plus size={14} aria-hidden />
          {t("Add unit")}
        </button>
      </div>
    </form>
  );
}

// ---------- Numbering ----------

export function NumberingAdmin() {
  const { data: schemes, isLoading } = useNumberingSchemes();
  const create = useCreateNumberingScheme();
  const [name, setName] = useState("");
  const [prefix, setPrefix] = useState("");
  const [padding, setPadding] = useState(6);
  const [suffix, setSuffix] = useState("");
  const [start, setStart] = useState(1);
  const example = prefix + String(start).padStart(padding, "0") + suffix;

  const submit = (e: FormEvent) => {
    e.preventDefault();
    create.mutate({ name: name.trim(), prefix, padding, suffix, start }, { onSuccess: () => setName("") });
  };

  return (
    <div className="cols">
      <Panel title={t("Numbering schemes")} note={t("Assign them to types under Types and attributes → Rules")} flush>
        {isLoading ? (
          <Loading />
        ) : !schemes?.length ? (
          <p className="empty">{t("No schemes yet. Types use the system's 10-digit sequence.")}</p>
        ) : (
          <table className="grid" aria-label={t("Numbering schemes")}>
            <thead>
              <tr>
                <th>{t("Name")}</th>
                <th>{t("Format")}</th>
                <th>{t("Next number")}</th>
              </tr>
            </thead>
            <tbody>
              {schemes.map((s) => (
                <tr key={s.id}>
                  <td>{s.name}</td>
                  <td className="nowrap muted">
                    <code>
                      {s.prefix}
                      {"#".repeat(s.padding)}
                      {s.suffix}
                    </code>
                  </td>
                  <td className="nowrap">
                    <code>{s.example}</code>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Panel>
      <form className="panel" aria-labelledby="new-scheme-title" onSubmit={submit} noValidate>
        <div className="panel-head">
          <h2 id="new-scheme-title">{t("New numbering scheme")}</h2>
        </div>
        <div className="panel-body" style={{ display: "grid", gap: 10 }}>
          <Problems error={create.error} />
          <div className="field">
            <label htmlFor="scheme-name">{t("Name")}</label>
            <input id="scheme-name" value={name} onChange={(e) => setName(e.target.value)} placeholder={t("e.g. Fasteners")} required />
          </div>
          <div className="attr-form cols-2">
            <div className="field">
              <label htmlFor="scheme-prefix">{t("Prefix")}</label>
              <input id="scheme-prefix" value={prefix} onChange={(e) => setPrefix(e.target.value)} placeholder="FST-" />
            </div>
            <div className="field">
              <label htmlFor="scheme-suffix">{t("Suffix")}</label>
              <input id="scheme-suffix" value={suffix} onChange={(e) => setSuffix(e.target.value)} />
            </div>
            <div className="field">
              <label htmlFor="scheme-padding">{t("Digits")}</label>
              <input
                id="scheme-padding"
                type="number"
                min={1}
                max={18}
                value={padding}
                onChange={(e) => setPadding(Math.max(1, Math.min(18, Number(e.target.value) || 1)))}
              />
            </div>
            <div className="field">
              <label htmlFor="scheme-start">{t("First number")}</label>
              <input
                id="scheme-start"
                type="number"
                min={0}
                value={start}
                onChange={(e) => setStart(Math.max(0, Number(e.target.value) || 0))}
              />
            </div>
          </div>
          <p className="muted" style={{ margin: 0 }}>
            {t("First number:")} <code>{example}</code>
          </p>
        </div>
        <div className="panel-foot">
          <button className="btn primary" type="submit" disabled={create.isPending || !name.trim()}>
            <Plus size={14} aria-hidden />
            {t("Create scheme")}
          </button>
        </div>
      </form>
    </div>
  );
}

// ---------- Team roles ----------

export function RolesAdmin() {
  const { data: roles, isLoading } = useTeamRoles();
  const [editing, setEditing] = useState<string | null>(null);

  return (
    <div className="cols">
      <Panel title={t("Team roles")} note={t("Give roles permissions with access rules on each context's Access tab")} flush>
        {isLoading ? (
          <Loading />
        ) : (
          <table className="grid" aria-label={t("Team roles")}>
            <thead>
              <tr>
                <th>{t("Role")}</th>
                <th>{t("Key")}</th>
                <th>{t("Description")}</th>
                <th>{t("Status")}</th>
                <th aria-label={t("Actions")} />
              </tr>
            </thead>
            <tbody>
              {roles?.map((r) =>
                editing === r.key ? (
                  <RoleEditRow key={r.key} role={r} onDone={() => setEditing(null)} />
                ) : (
                  <tr key={r.key} className={r.active ? undefined : "inactive"}>
                    <td className="nowrap">{pickLabel(r.labels, r.key)}</td>
                    <td className="nowrap">
                      <code>{r.key}</code>
                    </td>
                    <td className="muted">{r.description}</td>
                    <td className="nowrap">{r.active ? (r.system ? t("Out of the box") : t("Active")) : t("Inactive")}</td>
                    <td style={{ width: 40 }}>
                      <button
                        className="btn small ghost icon"
                        aria-label={t("Edit {name}", { name: pickLabel(r.labels, r.key) })}
                        onClick={() => setEditing(r.key)}
                      >
                        <Pencil size={13} aria-hidden />
                      </button>
                    </td>
                  </tr>
                ),
              )}
            </tbody>
          </table>
        )}
      </Panel>
      <NewRolePanel />
    </div>
  );
}

function RoleEditRow({ role, onDone }: { role: TeamRoleDefinition; onDone: () => void }) {
  const update = useUpdateRole();
  const [label, setLabel] = useState(pickLabel(role.labels, role.key));
  const [description, setDescription] = useState(role.description ?? "");
  const [active, setActive] = useState(role.active);
  return (
    <tr>
      <td>
        <input aria-label={t("Role label")} value={label} onChange={(e) => setLabel(e.target.value)} />
      </td>
      <td className="nowrap">
        <code>{role.key}</code>
      </td>
      <td>
        <input aria-label={t("Role description")} value={description} onChange={(e) => setDescription(e.target.value)} />
        <Problems error={update.error} />
      </td>
      <td className="nowrap">
        <label className="check" title={role.system ? t("Out-of-the-box roles stay active") : undefined}>
          <input type="checkbox" checked={active} disabled={role.system} onChange={(e) => setActive(e.target.checked)} />
          {t("Active")}
        </label>
      </td>
      <td className="nowrap">
        <button
          className="btn small primary"
          disabled={update.isPending || !label.trim()}
          onClick={() =>
            update.mutate(
              {
                key: role.key,
                body: {
                  labels: { ...role.labels, en: label.trim() },
                  description: description.trim() || null,
                  active,
                  sortOrder: role.sortOrder,
                },
              },
              { onSuccess: onDone },
            )
          }
        >
          {t("Save")}
        </button>{" "}
        <button className="btn small ghost" onClick={onDone}>
          {t("Cancel")}
        </button>
      </td>
    </tr>
  );
}

function NewRolePanel() {
  const create = useCreateRole();
  const { data: roles } = useTeamRoles();
  // New roles go after the existing ones in pickers.
  const nextSortOrder = Math.max(0, ...(roles ?? []).map((r) => r.sortOrder)) + 10;
  const [label, setLabel] = useState("");
  const [key, setKey] = useState("");
  const [keyEdited, setKeyEdited] = useState(false);
  const [description, setDescription] = useState("");
  const derived = label
    .trim()
    .toUpperCase()
    .replace(/[^A-Z0-9]+/g, "_")
    .replace(/^_+|_+$/g, "");
  const effectiveKey = keyEdited ? key : derived;

  const submit = (e: FormEvent) => {
    e.preventDefault();
    create.mutate(
      { key: effectiveKey, labels: { en: label.trim() }, description: description.trim() || null, sortOrder: nextSortOrder },
      {
        onSuccess: () => {
          setLabel("");
          setKey("");
          setKeyEdited(false);
          setDescription("");
        },
      },
    );
  };

  return (
    <form className="panel" aria-labelledby="new-role-title" onSubmit={submit} noValidate>
      <div className="panel-head">
        <h2 id="new-role-title">{t("New role")}</h2>
      </div>
      <div className="panel-body" style={{ display: "grid", gap: 10 }}>
        <Problems error={create.error} />
        <div className="field">
          <label htmlFor="role-label">{t("Label")}</label>
          <input
            id="role-label"
            value={label}
            onChange={(e) => setLabel(e.target.value)}
            placeholder={t("e.g. Design reviewer")}
            required
          />
        </div>
        <div className="field">
          <label htmlFor="role-key">{t("Key")}</label>
          <input
            id="role-key"
            value={effectiveKey}
            onChange={(e) => {
              setKeyEdited(true);
              setKey(e.target.value.toUpperCase());
            }}
          />
          <span className="hint">{t("UPPER_SNAKE_CASE, used by the API. Can't be changed later.")}</span>
        </div>
        <div className="field">
          <label htmlFor="role-description">{t("Description")}</label>
          <input id="role-description" value={description} onChange={(e) => setDescription(e.target.value)} />
        </div>
        <p className="muted" style={{ margin: 0 }}>
          {t("A new role can do nothing until an access rule grants it permissions.")}
        </p>
      </div>
      <div className="panel-foot">
        <button className="btn primary" type="submit" disabled={create.isPending || !label.trim() || !effectiveKey}>
          <Plus size={14} aria-hidden />
          {t("Create role")}
        </button>
      </div>
    </form>
  );
}
