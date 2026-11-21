import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowDown, ArrowUp, Columns3, FileSpreadsheet, Filter, Save, Share2, Trash2, X } from "lucide-react";
import { useState } from "react";
import { api, unwrap, type Schemas } from "../api/client";
import { useLifecycles, useMe, useTypes, type AttributeDefinition, type VersionedObject } from "../api/queries";
import { pickLabel } from "./AttributeForm";
import { Dialog, ErrorBanner, MenuButton, typeLabel } from "./common";
import { COLUMN_LABELS, DEFAULT_COLUMNS, columnLabel, sortRows, type BuiltinColumn, type ColumnKey } from "./ObjectTable";

export type ViewDefinition = Schemas["TableViewDefinition"];
type TableView = Schemas["TableView"];

export const DEFAULT_VIEW: ViewDefinition = {
  columns: DEFAULT_COLUMNS,
  sortColumn: "number",
  sortDescending: false,
  text: "",
  states: [],
  typeIds: [],
  attributes: {},
};

/** The rows a view shows: its filters, then its sort. */
export function applyView(
  rows: VersionedObject[],
  view: ViewDefinition,
  text: (o: VersionedObject, c: ColumnKey) => string,
): VersionedObject[] {
  const t = view.text?.trim().toLowerCase();
  const filtered = rows.filter(
    (o) =>
      (!t || o.number.toLowerCase().includes(t) || o.name.toLowerCase().includes(t)) &&
      (!view.states.length || view.states.includes(o.latest.state)) &&
      (!view.typeIds.length || view.typeIds.includes(o.typeId)) &&
      Object.entries(view.attributes).every(([k, v]) => text(o, `attr:${k}`).toLowerCase().includes(v.toLowerCase())),
  );
  return sortRows(filtered, (view.sortColumn ?? "number") as ColumnKey, !!view.sortDescending, text);
}

/** Downloads [rows] as an Excel workbook: the columns and cells as shown. */
export async function exportXlsx(
  name: string,
  columns: ColumnKey[],
  rows: VersionedObject[],
  definitions: Map<string, AttributeDefinition>,
  text: (o: VersionedObject, c: ColumnKey) => string,
) {
  // People columns hold user ids: written as names, as on screen.
  const people = new Map<string, string>();
  if (columns.some((c) => c === "owner" || c === "checkedOutBy")) {
    const ids = [...new Set(rows.flatMap((o) => [o.owner, o.checkedOutBy]).filter((x): x is string => !!x))];
    await Promise.all(
      ids.map(async (id) => {
        const r = await api.GET("/api/v1/principals/{id}", { params: { path: { id } } });
        people.set(id, r.data?.displayName ?? id);
      }),
    );
  }
  const cells = rows.map((o) =>
    columns.map((c) => {
      if (c === "owner" || c === "checkedOutBy") return people.get(text(o, c)) ?? text(o, c);
      const v = text(o, c);
      if (c === "modified" || c === "created") return v ? new Date(v).toLocaleString() : "";
      const def = c.startsWith("attr:") ? definitions.get(c.slice(5)) : undefined;
      if (def && (def.dataType === "INTEGER" || def.dataType === "REAL") && v !== "" && !Number.isNaN(Number(v))) return Number(v);
      return v;
    }),
  );
  const { data, error } = await api.POST("/api/v1/export/xlsx", {
    body: { sheetName: name, columns: columns.map((c) => columnLabel(c, definitions)), rows: cells },
    parseAs: "blob",
    headers: { Accept: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" },
  });
  if (error || !data) throw new Error("The export failed");
  const url = URL.createObjectURL(data as Blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = `${name}.xlsx`;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

/**
 * Table views (roadmap 3.7): which columns, filters and sort; saved by name, for yourself or
 * everyone; exported to Excel as shown.
 */
export function ViewBar({
  table,
  view,
  onChange,
  definitions,
  presentTypes,
  onExport,
  exporting,
}: {
  table: string;
  view: ViewDefinition;
  onChange: (v: ViewDefinition) => void;
  definitions: Map<string, AttributeDefinition>;
  presentTypes: string[];
  onExport: () => void;
  exporting: boolean;
}) {
  const qc = useQueryClient();
  const { data: me } = useMe();
  const { data: views } = useQuery({
    queryKey: ["tableViews", table],
    queryFn: () => unwrap(api.GET("/api/v1/table-views", { params: { query: { table } } })),
  });
  const [current, setCurrent] = useState<TableView | null>(null);
  const [dialog, setDialog] = useState<"columns" | "save" | "filter" | null>(null);
  const remove = useMutation({
    mutationFn: (id: string) => unwrap(api.DELETE("/api/v1/table-views/{id}", { params: { path: { id } } })),
    onSuccess: () => {
      setCurrent(null);
      onChange(DEFAULT_VIEW);
      void qc.invalidateQueries({ queryKey: ["tableViews", table] });
    },
  });
  const filters = view.states.length + view.typeIds.length + Object.keys(view.attributes).length;

  return (
    <div className="view-bar">
      <MenuButton label={current ? current.name : "Default view"} className="btn small" align="left">
        {(close) => (
          <div className="views-menu">
            <button
              role="menuitem"
              onClick={() => {
                setCurrent(null);
                onChange(DEFAULT_VIEW);
                close();
              }}
            >
              Default view
            </button>
            {views?.map((v) => (
              <div key={v.id} className="view-row">
                <button
                  role="menuitem"
                  onClick={() => {
                    setCurrent(v);
                    onChange({ ...DEFAULT_VIEW, ...v.definition });
                    close();
                  }}
                >
                  {v.name}
                </button>
                {v.shared && <Share2 size={11} className="icon-muted" aria-label="Shared" />}
                {v.owner === me?.user.id && (
                  <button className="icon-btn" aria-label={`Delete view ${v.name}`} onClick={() => remove.mutate(v.id)}>
                    <Trash2 size={12} />
                  </button>
                )}
              </div>
            ))}
          </div>
        )}
      </MenuButton>
      <input
        className="filter"
        type="search"
        aria-label="Filter objects"
        placeholder="Filter by number or name"
        value={view.text ?? ""}
        onChange={(e) => onChange({ ...view, text: e.target.value })}
      />
      <button className="btn small" onClick={() => setDialog("filter")}>
        <Filter size={13} aria-hidden /> Filters{filters ? ` (${filters})` : ""}
      </button>
      <button className="btn small" onClick={() => setDialog("columns")}>
        <Columns3 size={13} aria-hidden /> Columns
      </button>
      <span className="grow" />
      <button className="btn small" onClick={() => setDialog("save")}>
        <Save size={13} aria-hidden /> Save view
      </button>
      <button className="btn small" onClick={onExport} disabled={exporting}>
        <FileSpreadsheet size={13} aria-hidden /> Export to Excel
      </button>
      {dialog === "columns" && (
        <ColumnsDialog
          view={view}
          definitions={definitions}
          onClose={() => setDialog(null)}
          onChange={(columns) => onChange({ ...view, columns })}
        />
      )}
      {dialog === "filter" && (
        <FilterDialog
          view={view}
          definitions={definitions}
          presentTypes={presentTypes}
          onClose={() => setDialog(null)}
          onChange={onChange}
        />
      )}
      {dialog === "save" && (
        <SaveViewDialog
          table={table}
          view={view}
          current={current?.owner === me?.user.id ? current : null}
          onClose={() => setDialog(null)}
          onSaved={setCurrent}
        />
      )}
    </div>
  );
}

function ColumnsDialog({
  view,
  definitions,
  onClose,
  onChange,
}: {
  view: ViewDefinition;
  definitions: Map<string, AttributeDefinition>;
  onClose: () => void;
  onChange: (columns: string[]) => void;
}) {
  const [columns, setColumns] = useState<string[]>(view.columns);
  const all: ColumnKey[] = [
    ...(Object.keys(COLUMN_LABELS) as BuiltinColumn[]),
    ...[...definitions.keys()].sort().map((n) => `attr:${n}` as ColumnKey),
  ];
  const move = (i: number, d: number) => {
    const next = [...columns];
    const [c] = next.splice(i, 1);
    next.splice(i + d, 0, c!);
    setColumns(next);
  };
  return (
    <Dialog
      open
      wide
      title="Columns"
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button
            className="btn primary"
            disabled={!columns.length}
            onClick={() => {
              onChange(columns);
              onClose();
            }}
          >
            Apply
          </button>
        </>
      }
    >
      <div className="columns-dialog">
        <fieldset className="check-list">
          <legend>Show</legend>
          {all.map((c) => (
            <label key={c} className="check">
              <input
                type="checkbox"
                checked={columns.includes(c)}
                onChange={() => setColumns(columns.includes(c) ? columns.filter((x) => x !== c) : [...columns, c])}
              />
              {columnLabel(c, definitions)}
              {c.startsWith("attr:") && <span className="muted small"> attribute</span>}
            </label>
          ))}
        </fieldset>
        <div>
          <div className="section-label">Order</div>
          <ol className="column-order">
            {columns.map((c, i) => (
              <li key={c}>
                <span className="grow">{columnLabel(c as ColumnKey, definitions)}</span>
                <button className="icon-btn" aria-label={`Move ${c} up`} disabled={i === 0} onClick={() => move(i, -1)}>
                  <ArrowUp size={12} />
                </button>
                <button className="icon-btn" aria-label={`Move ${c} down`} disabled={i === columns.length - 1} onClick={() => move(i, 1)}>
                  <ArrowDown size={12} />
                </button>
                <button className="icon-btn" aria-label={`Hide ${c}`} onClick={() => setColumns(columns.filter((x) => x !== c))}>
                  <X size={12} />
                </button>
              </li>
            ))}
          </ol>
        </div>
      </div>
    </Dialog>
  );
}

function FilterDialog({
  view,
  definitions,
  presentTypes,
  onClose,
  onChange,
}: {
  view: ViewDefinition;
  definitions: Map<string, AttributeDefinition>;
  presentTypes: string[];
  onClose: () => void;
  onChange: (v: ViewDefinition) => void;
}) {
  const { data: types } = useTypes();
  const { data: lifecycles } = useLifecycles();
  const [states, setStates] = useState(view.states);
  const [typeIds, setTypeIds] = useState(view.typeIds);
  const [attributes, setAttributes] = useState<[string, string][]>(Object.entries(view.attributes));
  const allStates = new Map<string, string>();
  for (const lc of lifecycles ?? []) for (const s of lc.states) if (!allStates.has(s.key)) allStates.set(s.key, pickLabel(s.labels, s.key));
  const toggle = (list: string[], set: (v: string[]) => void, v: string) =>
    set(list.includes(v) ? list.filter((x) => x !== v) : [...list, v]);
  return (
    <Dialog
      open
      wide
      title="Filters"
      onClose={onClose}
      footer={
        <>
          <button
            className="btn"
            onClick={() => {
              onChange({ ...view, states: [], typeIds: [], attributes: {} });
              onClose();
            }}
          >
            Clear all
          </button>
          <button
            className="btn primary"
            onClick={() => {
              onChange({ ...view, states, typeIds, attributes: Object.fromEntries(attributes.filter(([k, v]) => k && v.trim())) });
              onClose();
            }}
          >
            Apply
          </button>
        </>
      }
    >
      <div className="attr-form">
        <fieldset className="check-list wide">
          <legend>State</legend>
          {[...allStates.entries()].map(([k, l]) => (
            <label key={k} className="check">
              <input type="checkbox" checked={states.includes(k)} onChange={() => toggle(states, setStates, k)} />
              {l}
            </label>
          ))}
        </fieldset>
        <fieldset className="check-list wide">
          <legend>Type</legend>
          {presentTypes.map((id) => (
            <label key={id} className="check">
              <input type="checkbox" checked={typeIds.includes(id)} onChange={() => toggle(typeIds, setTypeIds, id)} />
              {typeLabel(types?.find((t) => t.id === id))}
            </label>
          ))}
        </fieldset>
        <fieldset className="members-box wide">
          <legend>Attribute contains</legend>
          {attributes.map(([k, v], i) => (
            <div key={i} className="row" style={{ gap: 6 }}>
              <select
                aria-label="Attribute"
                value={k}
                onChange={(e) => setAttributes(attributes.map((a, j) => (j === i ? [e.target.value, a[1]] : a)))}
              >
                <option value="">Choose…</option>
                {[...definitions.values()].map((d) => (
                  <option key={d.name} value={d.name}>
                    {pickLabel(d.labels, d.name)}
                  </option>
                ))}
              </select>
              <input
                aria-label="Contains"
                value={v}
                onChange={(e) => setAttributes(attributes.map((a, j) => (j === i ? [a[0], e.target.value] : a)))}
              />
              <button className="icon-btn" aria-label="Remove" onClick={() => setAttributes(attributes.filter((_, j) => j !== i))}>
                <X size={13} />
              </button>
            </div>
          ))}
          <button className="btn small" style={{ justifySelf: "start" }} onClick={() => setAttributes([...attributes, ["", ""]])}>
            Add a condition
          </button>
        </fieldset>
      </div>
    </Dialog>
  );
}

function SaveViewDialog({
  table,
  view,
  current,
  onClose,
  onSaved,
}: {
  table: string;
  view: ViewDefinition;
  current: TableView | null;
  onClose: () => void;
  onSaved: (v: TableView) => void;
}) {
  const qc = useQueryClient();
  const [name, setName] = useState(current?.name ?? "");
  const [shared, setShared] = useState(current?.shared ?? false);
  const [asNew, setAsNew] = useState(!current);
  const save = useMutation({
    mutationFn: () => {
      const body = { table, name, definition: view, shared };
      return current && !asNew
        ? unwrap(api.PUT("/api/v1/table-views/{id}", { params: { path: { id: current.id } }, body }))
        : unwrap(api.POST("/api/v1/table-views", { body }));
    },
    onSuccess: (v) => {
      void qc.invalidateQueries({ queryKey: ["tableViews", table] });
      onSaved(v);
      onClose();
    },
  });
  return (
    <Dialog
      open
      title="Save this view"
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button className="btn primary" disabled={save.isPending || !name.trim()} onClick={() => save.mutate()}>
            Save
          </button>
        </>
      }
    >
      <div className="attr-form">
        <ErrorBanner error={save.error} />
        {current && (
          <label className="check wide">
            <input type="checkbox" checked={asNew} onChange={(e) => setAsNew(e.target.checked)} />
            Save as a new view (instead of changing “{current.name}”)
          </label>
        )}
        <div className="field wide">
          <label htmlFor="view-name">Name</label>
          <input id="view-name" value={name} onChange={(e) => setName(e.target.value)} autoFocus />
          <span className="hint">Columns, filters and sort, as they are now.</span>
        </div>
        <label className="check wide">
          <input type="checkbox" checked={shared} onChange={(e) => setShared(e.target.checked)} />
          Share it with everyone
        </label>
      </div>
    </Dialog>
  );
}
