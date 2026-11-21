import { useQueries } from "@tanstack/react-query";
import { ArrowDown, ArrowUp } from "lucide-react";
import { useMemo, useState, type ReactNode } from "react";
import { Link } from "react-router";
import { api, unwrap } from "../api/client";
import { useContextById, useEnumerations, useTypes, type AttributeDefinition, type VersionedObject } from "../api/queries";
import { t } from "../i18n/i18n";
import { formatAttribute, pickLabel } from "./AttributeForm";
import { formatDate, PrincipalName, StateBadge, stateLabel, TypeIcon, typeLabel } from "./common";

/** Built-in columns; attribute columns are `attr:<name>` (roadmap 3.7). */
export type BuiltinColumn =
  | "number"
  | "name"
  | "type"
  | "version"
  | "state"
  | "context"
  | "checkedOutBy"
  | "owner"
  | "modified"
  | "created";
export type ColumnKey = BuiltinColumn | `attr:${string}`;

/** English names of the built-in columns; [columnLabel] gives them in the user's language. */
export const COLUMN_LABELS: Record<BuiltinColumn, string> = {
  number: "Number",
  name: "Name",
  type: "Type",
  version: "Version",
  state: "State",
  context: "Context",
  checkedOutBy: "Checked out by",
  owner: "Owner",
  modified: "Last modified",
  created: "Created",
};

export const DEFAULT_COLUMNS: ColumnKey[] = ["number", "name", "type", "version", "state", "checkedOutBy", "modified"];

/** Attribute definitions of the types of [objects], by name (the first type's wins on a clash). */
export function useAttributeDefinitions(typeIds: string[]): Map<string, AttributeDefinition> {
  const unique = [...new Set(typeIds)].slice(0, 20);
  return useQueries({
    queries: unique.map((id) => ({
      queryKey: ["typeAttributes", id],
      queryFn: () => unwrap(api.GET("/api/v1/types/{typeId}/attributes", { params: { path: { typeId: id } } })),
      staleTime: 5 * 60_000,
    })),
    combine: (results) => {
      const map = new Map<string, AttributeDefinition>();
      for (const r of results) for (const d of r.data ?? []) if (!map.has(d.name)) map.set(d.name, d);
      return map;
    },
  });
}

/**
 * What a cell shows as text: for sorting, filtering and the Excel export, so all three agree with
 * what's on screen.
 */
export function useCellText(definitions: Map<string, AttributeDefinition>) {
  const { data: types } = useTypes();
  const { data: enumerations } = useEnumerations();
  return (o: VersionedObject, column: ColumnKey): string => {
    if (column.startsWith("attr:")) {
      const name = column.slice(5);
      const v = (o.latest.attributes as Record<string, unknown>)[name];
      return v == null ? "" : formatAttribute(definitions.get(name), v, enumerations);
    }
    switch (column as BuiltinColumn) {
      case "number":
        return o.number;
      case "name":
        return o.name;
      case "type":
        return typeLabel(types?.find((t) => t.id === o.typeId));
      case "version":
        return o.version;
      case "state":
        return stateLabel(o.latest.state);
      case "modified":
        return o.latest.checkedInAt ?? o.latest.createdAt;
      case "created":
        return o.createdAt;
      case "checkedOutBy":
        return o.checkedOutBy ?? "";
      case "owner":
        return o.owner;
      default:
        return o.contextId;
    }
  };
}

export function columnLabel(column: ColumnKey, definitions: Map<string, AttributeDefinition>): string {
  if (column.startsWith("attr:")) {
    const name = column.slice(5);
    return pickLabel(definitions.get(name)?.labels, name);
  }
  switch (column as BuiltinColumn) {
    case "number":
      return t("Number");
    case "name":
      return t("Name");
    case "type":
      return t("Type");
    case "version":
      return t("Version");
    case "state":
      return t("State");
    case "context":
      return t("Context");
    case "checkedOutBy":
      return t("Checked out by");
    case "owner":
      return t("Owner");
    case "modified":
      return t("Last modified");
    case "created":
      return t("Created");
  }
  return COLUMN_LABELS[column as BuiltinColumn] ?? column;
}

/** Sorts by a column's text: numbers in it compare as numbers ("A.10" after "A.9"). */
export function sortRows(
  objects: VersionedObject[],
  column: ColumnKey,
  desc: boolean,
  text: (o: VersionedObject, c: ColumnKey) => string,
): VersionedObject[] {
  const key = (o: VersionedObject) =>
    column === "version" ? o.latest.revision.padStart(4, " ") + String(o.latest.iteration).padStart(6, "0") : text(o, column).toLowerCase();
  const rows = [...objects].sort((a, b) => key(a).localeCompare(key(b), undefined, { numeric: true }));
  return desc ? rows.reverse() : rows;
}

/** Dense, sortable table of versioned objects. Sorting is on the loaded rows. */
export function ObjectTable({
  objects,
  showContext = false,
  footer,
  label = t("Objects"),
  selected,
  onSelect,
  columns: chosen,
  sort: controlledSort,
  onSort,
}: {
  objects: VersionedObject[];
  showContext?: boolean;
  footer?: ReactNode;
  label?: string;
  /** With both, rows can be ticked for mass operations (roadmap 3.5). */
  selected?: Set<string>;
  onSelect?: (ids: Set<string>) => void;
  /** Which columns, in order (roadmap 3.7); the default set otherwise. */
  columns?: ColumnKey[];
  sort?: { column: ColumnKey; desc: boolean };
  onSort?: (sort: { column: ColumnKey; desc: boolean }) => void;
}) {
  const definitions = useAttributeDefinitions(objects.map((o) => o.typeId));
  const text = useCellText(definitions);
  const [ownSort, setOwnSort] = useState<{ column: ColumnKey; desc: boolean }>({ column: "number", desc: false });
  const sort = controlledSort ?? ownSort;
  const setSort = onSort ?? setOwnSort;
  const selectable = !!selected && !!onSelect;
  const toggle = (id: string) => {
    const next = new Set(selected);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    onSelect?.(next);
  };
  const allSelected = selectable && objects.length > 0 && objects.every((o) => selected.has(o.id));
  const columns: ColumnKey[] =
    chosen ?? (showContext ? [...DEFAULT_COLUMNS.slice(0, 5), "context", ...DEFAULT_COLUMNS.slice(5)] : DEFAULT_COLUMNS);

  const sorted = useMemo(() => sortRows(objects, sort.column, sort.desc, text), [objects, sort, text]);

  const header = (column: ColumnKey) => {
    const active = sort.column === column;
    return (
      <th key={column} aria-sort={active ? (sort.desc ? "descending" : "ascending") : undefined}>
        <button className="sort-btn" onClick={() => setSort({ column, desc: active ? !sort.desc : false })}>
          {columnLabel(column, definitions)}
          {active && (sort.desc ? <ArrowDown size={12} aria-hidden /> : <ArrowUp size={12} aria-hidden />)}
        </button>
      </th>
    );
  };

  const cell = (o: VersionedObject, column: ColumnKey) => {
    switch (column) {
      case "number":
        return (
          <td key={column} className="nowrap">
            <Link className="num-link" to={`/objects/${o.id}`}>
              {o.number}
            </Link>
          </td>
        );
      case "name":
        return (
          <td key={column}>
            <Link className="name-link" to={`/objects/${o.id}`}>
              {o.name}
            </Link>
          </td>
        );
      case "version":
        return (
          <td key={column} className="nowrap version">
            {o.version}
          </td>
        );
      case "state":
        return (
          <td key={column}>
            <StateBadge state={o.latest.state} />
          </td>
        );
      case "context":
        return (
          <td key={column} className="nowrap">
            <ContextName id={o.contextId} />
          </td>
        );
      case "checkedOutBy":
      case "owner": {
        const who = column === "owner" ? o.owner : o.checkedOutBy;
        return (
          <td key={column} className="nowrap">
            {who ? <PrincipalName id={who} /> : <span className="muted">—</span>}
          </td>
        );
      }
      case "modified":
      case "created":
        return (
          <td key={column} className="nowrap muted date">
            {formatDate(column === "created" ? o.createdAt : o.latest.checkedInAt)}
          </td>
        );
      default: {
        const value = text(o, column);
        return (
          <td key={column} className={column === "type" ? "nowrap" : undefined}>
            {value || <span className="muted">—</span>}
          </td>
        );
      }
    }
  };

  return (
    <>
      <div className="grid-wrap">
        <table className="grid" aria-label={label}>
          <thead>
            <tr>
              {selectable && (
                <th className="check-cell">
                  <input
                    type="checkbox"
                    aria-label={t("Select all")}
                    checked={allSelected}
                    onChange={() => onSelect(allSelected ? new Set() : new Set(objects.map((o) => o.id)))}
                  />
                </th>
              )}
              <th className="icon-cell">
                <span className="sr-only">{t("Icon")}</span>
              </th>
              {columns.map(header)}
            </tr>
          </thead>
          <tbody>
            {sorted.map((o) => (
              <tr key={o.id} className={selectable && selected.has(o.id) ? "selected" : undefined}>
                {selectable && (
                  <td className="check-cell">
                    <input
                      type="checkbox"
                      aria-label={t("Select {number}", { number: o.number })}
                      checked={selected.has(o.id)}
                      onChange={() => toggle(o.id)}
                    />
                  </td>
                )}
                <td className="icon-cell">
                  <TypeIcon typeId={o.typeId} />
                </td>
                {columns.map((c) => cell(o, c))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {footer && <div className="grid-foot">{footer}</div>}
    </>
  );
}

function ContextName({ id }: { id: string }) {
  const { data } = useContextById(id);
  return data ? <Link to={`/contexts/${id}`}>{data.name}</Link> : <span className="muted">…</span>;
}
