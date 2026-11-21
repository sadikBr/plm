import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus, Trash2 } from "lucide-react";
import { useState } from "react";
import { Link } from "react-router";
import { api, unwrap, type Schemas } from "../api/client";
import type { VersionedObject } from "../api/queries";
import { ErrorBanner, Panel } from "../components/common";
import { t } from "../i18n/i18n";

type Table = Schemas["FamilyTable"];
type Column = Schemas["FamilyColumn"];

const shown = (v: unknown) => (v === null || v === undefined ? "" : String(v));
/** Numbers stay numbers in the table. */
const parse = (v: string): unknown => (v.trim() === "" ? null : /^-?\d+(\.\d+)?$/.test(v.trim()) ? Number(v.trim()) : v.trim());

/** Family tables (roadmap 6.3): a generic's instances, or the family an instance belongs to. */
export function FamilyPanel({ obj, mine }: { obj: VersionedObject; mine: boolean }) {
  const { data } = useQuery({
    queryKey: ["family", obj.id, mine],
    queryFn: () =>
      mine
        ? unwrap(api.GET("/api/v1/objects/{id}/cad/family/table", { params: { path: { id: obj.id }, query: { workingCopy: true } } })).then(
            (table) => ({ role: table.columns.length ? "GENERIC" : "NONE", table }) as Schemas["FamilyMembership"],
          )
        : unwrap(api.GET("/api/v1/objects/{id}/cad/family", { params: { path: { id: obj.id } } })),
  });
  if (!data) return null;
  if (mine) return <FamilyEditor obj={obj} table={data.table!} />;
  if (data.role === "NONE") return null;
  const table = data.table!;
  return (
    <Panel
      title={t("Family table")}
      note={
        data.role === "INSTANCE" ? (
          <>
            {t("An instance of")} <Link to={`/objects/${table.generic.id}?tab=cad`}>{table.generic.number}</Link> {table.generic.name},{" "}
            {t("sharing its file.")}
          </>
        ) : (
          t("Instances share this model's file; each is a CAD document of its own.")
        )
      }
      flush
    >
      <FamilyGrid table={table} highlight={obj.id} />
    </Panel>
  );
}

function FamilyGrid({ table, highlight }: { table: Table; highlight?: string }) {
  return (
    <table className="grid family-grid" aria-label={t("Family table")}>
      <thead>
        <tr>
          <th>{t("Instance")}</th>
          {table.columns.map((c) => (
            <th key={c.name} className="num">
              {c.label ?? c.name}
              {c.unit && <span className="muted"> ({c.unit})</span>}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {table.instances.map((i) => (
          <tr key={i.model.id} className={i.model.id === highlight ? "selected" : ""}>
            <td>
              <Link to={`/objects/${i.model.id}?tab=cad`}>{i.model.number}</Link> {i.model.name}
            </td>
            {table.columns.map((c) => (
              <td key={c.name} className="num mono">
                {shown(i.values[c.name])}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function FamilyEditor({ obj, table }: { obj: VersionedObject; table: Table }) {
  const qc = useQueryClient();
  const [columns, setColumns] = useState<Column[]>(table.columns.length ? table.columns : [{ name: "", label: "", unit: "" }]);
  const [name, setName] = useState("");
  const [values, setValues] = useState<Record<string, string>>({});
  const refresh = () => void qc.invalidateQueries({ queryKey: ["family", obj.id] });
  const path = { params: { path: { id: obj.id } } };
  const saveColumns = useMutation({
    mutationFn: () =>
      unwrap(
        api.PUT("/api/v1/objects/{id}/cad/family/columns", {
          ...path,
          body: columns
            .filter((c) => c.name.trim())
            .map((c) => ({ name: c.name.trim(), label: c.label?.trim() || null, unit: c.unit?.trim() || null })),
        }),
      ),
    onSuccess: refresh,
  });
  const add = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/api/v1/objects/{id}/cad/family/instances", {
          ...path,
          body: { name: name.trim(), values: Object.fromEntries(Object.entries(values).map(([k, v]) => [k, parse(v)])) },
        }),
      ),
    onSuccess: () => {
      setName("");
      setValues({});
      refresh();
    },
  });
  const setRow = useMutation({
    mutationFn: ({ instanceId, row }: { instanceId: string; row: Record<string, unknown> }) =>
      unwrap(
        api.PUT("/api/v1/objects/{id}/cad/family/instances/{instanceId}", { params: { path: { id: obj.id, instanceId } }, body: row }),
      ),
    onSuccess: refresh,
  });
  const remove = useMutation({
    mutationFn: (instanceId: string) =>
      unwrap(api.DELETE("/api/v1/objects/{id}/cad/family/instances/{instanceId}", { params: { path: { id: obj.id, instanceId } } })),
    onSuccess: refresh,
  });
  const cols = table.columns;
  return (
    <Panel
      title={t("Family table")}
      note={t("Your working copy. Columns are the model's parameters; each row is an instance, a CAD document sharing this file.")}
    >
      <div className="family-columns">
        <span className="muted small">{t("Columns")}</span>
        {columns.map((c, i) => (
          <div key={i} className="row" style={{ gap: 4 }}>
            <input
              aria-label={t("Parameter")}
              placeholder={t("parameter, e.g. length")}
              value={c.name}
              onChange={(e) => setColumns(columns.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)))}
            />
            <input
              aria-label={t("Label")}
              placeholder={t("label")}
              value={c.label ?? ""}
              onChange={(e) => setColumns(columns.map((x, j) => (j === i ? { ...x, label: e.target.value } : x)))}
            />
            <input
              aria-label={t("Unit")}
              placeholder={t("unit")}
              style={{ width: 70 }}
              value={c.unit ?? ""}
              onChange={(e) => setColumns(columns.map((x, j) => (j === i ? { ...x, unit: e.target.value } : x)))}
            />
          </div>
        ))}
        <div className="row" style={{ gap: 6 }}>
          <button className="btn small ghost" onClick={() => setColumns([...columns, { name: "", label: "", unit: "" }])}>
            <Plus size={13} aria-hidden /> {t("Column")}
          </button>
          <button className="btn small" disabled={saveColumns.isPending} onClick={() => saveColumns.mutate()}>
            {t("Save the columns")}
          </button>
        </div>
      </div>
      {cols.length > 0 && (
        <table className="grid family-grid" aria-label={t("Family table")}>
          <thead>
            <tr>
              <th>{t("Instance")}</th>
              {cols.map((c) => (
                <th key={c.name}>
                  {c.label ?? c.name}
                  {c.unit && <span className="muted"> ({c.unit})</span>}
                </th>
              ))}
              <th />
            </tr>
          </thead>
          <tbody>
            {table.instances.map((i) => (
              <tr key={i.model.id}>
                <td className="nowrap">
                  <Link to={`/objects/${i.model.id}?tab=cad`}>{i.model.number}</Link> {i.model.name}
                </td>
                {cols.map((c) => (
                  <td key={c.name}>
                    <input
                      aria-label={`${i.model.number} ${c.name}`}
                      defaultValue={shown(i.values[c.name])}
                      onBlur={(e) => {
                        if (e.target.value === shown(i.values[c.name])) return;
                        setRow.mutate({ instanceId: i.model.id, row: { ...i.values, [c.name]: parse(e.target.value) } });
                      }}
                    />
                  </td>
                ))}
                <td className="row-actions">
                  <button className="btn ghost small" aria-label={t("Take out of the table")} onClick={() => remove.mutate(i.model.id)}>
                    <Trash2 size={13} aria-hidden />
                  </button>
                </td>
              </tr>
            ))}
            <tr className="new-row">
              <td>
                <input
                  aria-label={t("New instance's name")}
                  placeholder={t("New instance, e.g. Hex bolt M8x30")}
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                />
              </td>
              {cols.map((c) => (
                <td key={c.name}>
                  <input
                    aria-label={`${t("New")} ${c.name}`}
                    value={values[c.name] ?? ""}
                    onChange={(e) => setValues({ ...values, [c.name]: e.target.value })}
                  />
                </td>
              ))}
              <td>
                <button className="btn primary small" disabled={!name.trim() || add.isPending} onClick={() => add.mutate()}>
                  {t("Add")}
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      )}
      <ErrorBanner error={saveColumns.error ?? add.error ?? setRow.error ?? remove.error} />
    </Panel>
  );
}
