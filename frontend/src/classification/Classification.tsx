import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { FolderTree, Plus, Trash2 } from "lucide-react";
import { useMemo, useState } from "react";
import { api, unwrap, type Schemas } from "../api/client";
import { useMe, type VersionedObject } from "../api/queries";
import { ErrorBanner, Loading, Panel, PrincipalName, formatDate } from "../components/common";
import { t } from "../i18n/i18n";

type ClassNode = Schemas["ClassNode"];
type Attr = Schemas["ClassAttribute"];

export const useClassTree = () => useQuery({ queryKey: ["classes"], queryFn: () => unwrap(api.GET("/api/v1/classes")), staleTime: 30_000 });

/** The tree, depth first, each class with its depth: for indented lists and pickers. */
export function flatten(tree: ClassNode[]): { node: ClassNode; depth: number }[] {
  const kids = new Map<string | null, ClassNode[]>();
  for (const n of tree) kids.set(n.parentId ?? null, [...(kids.get(n.parentId ?? null) ?? []), n]);
  const out: { node: ClassNode; depth: number }[] = [];
  const walk = (parent: string | null, depth: number) => {
    for (const n of kids.get(parent) ?? []) {
      out.push({ node: n, depth });
      walk(n.id, depth + 1);
    }
  };
  walk(null, 0);
  return out;
}

/** Attributes that apply in a class: its ancestors' first. */
export function inherited(tree: ClassNode[], id: string): Attr[] {
  const byId = new Map(tree.map((n) => [n.id, n]));
  const path: ClassNode[] = [];
  for (let at = byId.get(id); at; at = at.parentId ? byId.get(at.parentId) : undefined) path.unshift(at);
  return path.flatMap((n) => n.attributes);
}

const typeLabel = (d: Attr["dataType"]) =>
  d === "NUMBER" ? t("Number") : d === "TEXT" ? t("Text") : d === "BOOLEAN" ? t("Yes or no") : t("Value list");

/** Administration › Classification (roadmap 7.11): the class tree and each class's attributes. */
export function ClassificationAdmin() {
  const qc = useQueryClient();
  const { data: tree, error } = useClassTree();
  const [selected, setSelected] = useState<string | null>(null);
  const [code, setCode] = useState("");
  const [name, setName] = useState("");
  const [attr, setAttr] = useState({ key: "", label: "", dataType: "NUMBER" as Attr["dataType"], unit: "", allowed: "", required: false });
  const refresh = () => void qc.invalidateQueries({ queryKey: ["classes"] });
  const create = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/classes", { body: { code, name, parentId: selected } })),
    onSuccess: (n) => {
      setCode("");
      setName("");
      setSelected(n.id);
      refresh();
    },
  });
  const addAttr = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/api/v1/classes/{id}/attributes", {
          params: { path: { id: selected! } },
          body: {
            ...attr,
            allowed: attr.allowed
              .split(",")
              .map((s) => s.trim())
              .filter(Boolean),
            unit: attr.unit || null,
          },
        }),
      ),
    onSuccess: () => {
      setAttr({ key: "", label: "", dataType: "NUMBER", unit: "", allowed: "", required: false });
      refresh();
    },
  });
  const removeAttr = useMutation({
    mutationFn: (attributeId: string) =>
      unwrap(api.DELETE("/api/v1/class-attributes/{attributeId}", { params: { path: { attributeId } } })),
    onSuccess: refresh,
  });
  const remove = useMutation({
    mutationFn: (id: string) => unwrap(api.DELETE("/api/v1/classes/{id}", { params: { path: { id } } })),
    onSuccess: () => {
      setSelected(null);
      refresh();
    },
  });
  const flat = useMemo(() => flatten(tree ?? []), [tree]);
  if (!tree) return error ? <ErrorBanner error={error} /> : <Loading />;
  const node = tree.find((n) => n.id === selected);
  const all = node ? inherited(tree, node.id) : [];
  return (
    <div className="class-admin">
      <Panel title={t("Class tree")} note={t("Classes group similar parts; their attributes apply to every class below them.")} flush>
        <ul className="class-tree" aria-label={t("Class tree")}>
          {flat.map(({ node: n, depth }) => (
            <li key={n.id} className={n.id === selected ? "selected" : ""} style={{ paddingLeft: 10 + depth * 18 }}>
              <button className="link-btn" onClick={() => setSelected(n.id)}>
                <FolderTree size={13} aria-hidden /> {n.name}
              </button>
              <code className="muted small">{n.code}</code>
              {n.parts > 0 && <span className="muted small">{t("{n} parts", { n: n.parts })}</span>}
            </li>
          ))}
          {!flat.length && <li className="empty small">{t("No classes yet.")}</li>}
        </ul>
        <form
          className="row option-form class-new"
          onSubmit={(e) => {
            e.preventDefault();
            create.mutate();
          }}
        >
          <input aria-label={t("Class code")} placeholder={t("Code")} value={code} onChange={(e) => setCode(e.target.value)} />
          <input aria-label={t("Class name")} placeholder={t("Name")} value={name} onChange={(e) => setName(e.target.value)} />
          <button className="btn small" disabled={!code.trim() || !name.trim() || create.isPending}>
            <Plus size={13} aria-hidden /> {node ? t("Class under {name}", { name: node.name }) : t("Top class")}
          </button>
        </form>
        <ErrorBanner error={create.error} />
      </Panel>
      {node && (
        <Panel
          title={node.name}
          note={node.description ?? t("Code {code}", { code: node.code })}
          actions={
            <button className="btn small ghost" onClick={() => remove.mutate(node.id)}>
              <Trash2 size={13} aria-hidden /> {t("Delete")}
            </button>
          }
          flush
        >
          <ErrorBanner error={remove.error ?? addAttr.error ?? removeAttr.error} />
          <table className="grid" aria-label={t("Attributes of {name}", { name: node.name })}>
            <thead>
              <tr>
                <th>{t("Attribute")}</th>
                <th>{t("Type")}</th>
                <th>{t("Unit or values")}</th>
                <th>{t("From")}</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {all.map((a) => (
                <tr key={a.id} className={a.classId === node.id ? "" : "inherited"}>
                  <td>
                    {a.label} <code className="muted small">{a.key}</code>
                    {a.required && <span className="muted small"> · {t("required")}</span>}
                  </td>
                  <td>{typeLabel(a.dataType)}</td>
                  <td>{a.dataType === "ENUM" ? a.allowed.join(", ") : (a.unit ?? "")}</td>
                  <td className="muted small">{a.classId === node.id ? t("this class") : tree.find((n) => n.id === a.classId)?.name}</td>
                  <td>
                    {a.classId === node.id && (
                      <button
                        className="icon-btn"
                        aria-label={t("Remove {label}", { label: a.label })}
                        onClick={() => removeAttr.mutate(a.id)}
                      >
                        <Trash2 size={12} />
                      </button>
                    )}
                  </td>
                </tr>
              ))}
              {!all.length && (
                <tr>
                  <td colSpan={5} className="empty">
                    {t("No attributes yet.")}
                  </td>
                </tr>
              )}
            </tbody>
          </table>
          <form
            className="row wrap class-attr-form"
            onSubmit={(e) => {
              e.preventDefault();
              addAttr.mutate();
            }}
          >
            <input
              aria-label={t("Attribute key")}
              placeholder={t("key, e.g. diameter")}
              value={attr.key}
              onChange={(e) => setAttr({ ...attr, key: e.target.value })}
            />
            <input
              aria-label={t("Attribute label")}
              placeholder={t("Label")}
              value={attr.label}
              onChange={(e) => setAttr({ ...attr, label: e.target.value })}
            />
            <select
              aria-label={t("Attribute type")}
              value={attr.dataType}
              onChange={(e) => setAttr({ ...attr, dataType: e.target.value as Attr["dataType"] })}
            >
              {(["NUMBER", "TEXT", "BOOLEAN", "ENUM"] as const).map((d) => (
                <option key={d} value={d}>
                  {typeLabel(d)}
                </option>
              ))}
            </select>
            {attr.dataType === "NUMBER" && (
              <input
                aria-label={t("Unit")}
                placeholder={t("Unit, e.g. mm")}
                value={attr.unit}
                onChange={(e) => setAttr({ ...attr, unit: e.target.value })}
              />
            )}
            {attr.dataType === "ENUM" && (
              <input
                aria-label={t("Values")}
                placeholder={t("Values, separated by commas")}
                value={attr.allowed}
                onChange={(e) => setAttr({ ...attr, allowed: e.target.value })}
              />
            )}
            <label className="check">
              <input type="checkbox" checked={attr.required} onChange={(e) => setAttr({ ...attr, required: e.target.checked })} />{" "}
              {t("Required")}
            </label>
            <button className="btn small" disabled={!attr.key.trim() || !attr.label.trim() || addAttr.isPending}>
              <Plus size={13} aria-hidden /> {t("Attribute")}
            </button>
          </form>
        </Panel>
      )}
    </div>
  );
}

const show = (a: Attr, v: unknown) =>
  v == null ? "—" : a.dataType === "BOOLEAN" ? (v ? t("Yes") : t("No")) : `${String(v)}${a.unit ? ` ${a.unit}` : ""}`;

/** A part's class and values (roadmap 7.11), edited on your working copy. */
export function ClassificationTab({ obj }: { obj: VersionedObject }) {
  const qc = useQueryClient();
  const { data: me } = useMe();
  const mine = !!me && obj.checkedOutBy === me.user.id;
  const { data: tree } = useClassTree();
  const { data, error } = useQuery({
    queryKey: ["classification", obj.id, mine],
    queryFn: () =>
      unwrap(api.GET("/api/v1/objects/{id}/classification", { params: { path: { id: obj.id }, query: { workingCopy: mine } } })),
  });
  const [classId, setClassId] = useState<string | undefined>();
  const [values, setValues] = useState<Record<string, string>>({});
  const save = useMutation({
    mutationFn: () =>
      unwrap(
        api.PUT("/api/v1/objects/{id}/classification", {
          params: { path: { id: obj.id } },
          body: { classId: chosen ?? null, values: Object.fromEntries(Object.entries(values).filter(([, v]) => v !== "")) },
        }),
      ),
    onSuccess: (r) => {
      qc.setQueryData(["classification", obj.id, mine], r);
      void qc.invalidateQueries({ queryKey: ["classes"] });
    },
  });
  const current = data?.path.at(-1)?.id;
  const chosen = classId ?? current;
  const attrs = tree && chosen ? inherited(tree, chosen) : (data?.attributes ?? []);
  const flat = useMemo(() => flatten(tree ?? []), [tree]);
  if (!data) return error ? <ErrorBanner error={error} /> : <Loading />;
  const value = (a: Attr) => values[a.key] ?? (data.values[a.key] != null ? String(data.values[a.key]) : "");
  return (
    <Panel
      title={t("Classification")}
      note={data.path.length ? data.path.map((n) => n.name).join(" › ") : t("Not classified.")}
      actions={
        mine && (
          <select
            aria-label={t("Class")}
            value={chosen ?? ""}
            onChange={(e) => setClassId(e.target.value || undefined)}
            style={{ width: 280 }}
          >
            <option value="">{t("Not classified")}</option>
            {flat.map(({ node, depth }) => (
              <option key={node.id} value={node.id}>
                {"  ".repeat(depth)}
                {node.name}
              </option>
            ))}
          </select>
        )
      }
      flush
    >
      <ErrorBanner error={save.error} />
      {attrs.length ? (
        <table className="grid" aria-label={t("Class attributes")}>
          <tbody>
            {attrs.map((a) => (
              <tr key={a.id}>
                <th className="nowrap" style={{ width: 240 }}>
                  {a.label}
                  {a.required && mine && " *"}
                </th>
                <td>
                  {!mine ? (
                    show(a, data.values[a.key])
                  ) : a.dataType === "ENUM" || a.dataType === "BOOLEAN" ? (
                    <select aria-label={a.label} value={value(a)} onChange={(e) => setValues({ ...values, [a.key]: e.target.value })}>
                      <option value="">—</option>
                      {(a.dataType === "ENUM" ? a.allowed : ["true", "false"]).map((v) => (
                        <option key={v} value={v}>
                          {a.dataType === "BOOLEAN" ? (v === "true" ? t("Yes") : t("No")) : v}
                        </option>
                      ))}
                    </select>
                  ) : (
                    <span className="row">
                      <input
                        aria-label={a.label}
                        value={value(a)}
                        onChange={(e) => setValues({ ...values, [a.key]: e.target.value })}
                        style={{ width: 200 }}
                      />
                      {a.unit && <span className="muted">{a.unit}</span>}
                    </span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <div className="empty small">{mine ? t("Choose a class to give its attributes values.") : t("Check it out to classify it.")}</div>
      )}
      {mine && (
        <div className="row spec-save">
          <span className="muted small">
            {data.updatedAt && (
              <>
                {t("Last set by")} <PrincipalName id={data.updatedBy} /> · {formatDate(data.updatedAt)}
              </>
            )}
          </span>
          <button className="btn small primary" style={{ marginLeft: "auto" }} disabled={save.isPending} onClick={() => save.mutate()}>
            {t("Save classification")}
          </button>
        </div>
      )}
    </Panel>
  );
}
