import { ArrowDown, ArrowUp } from "lucide-react";
import { useMemo, useState, type ReactNode } from "react";
import { Link } from "react-router";
import { useContextById, useTypes, type VersionedObject } from "../api/queries";
import { formatDate, PrincipalName, StateBadge, stateLabel, TypeIcon, typeLabel } from "./common";

type Column = "number" | "name" | "type" | "version" | "state" | "context" | "checkedOutBy" | "modified";

const LABELS: Record<Column, string> = {
  number: "Number",
  name: "Name",
  type: "Type",
  version: "Version",
  state: "State",
  context: "Context",
  checkedOutBy: "Checked out by",
  modified: "Last modified",
};

/** Dense, sortable table of versioned objects. Sorting is on the loaded rows. */
export function ObjectTable({
  objects,
  showContext = false,
  footer,
  label = "Objects",
}: {
  objects: VersionedObject[];
  showContext?: boolean;
  footer?: ReactNode;
  label?: string;
}) {
  const { data: types } = useTypes();
  const [sort, setSort] = useState<{ column: Column; desc: boolean }>({ column: "number", desc: false });
  const columns: Column[] = [
    "number",
    "name",
    "type",
    "version",
    "state",
    ...(showContext ? (["context"] as Column[]) : []),
    "checkedOutBy",
    "modified",
  ];

  const sorted = useMemo(() => {
    const typeName = (o: VersionedObject) => typeLabel(types?.find((t) => t.id === o.typeId));
    const key = (o: VersionedObject): string => {
      switch (sort.column) {
        case "number":
          return o.number;
        case "name":
          return o.name.toLowerCase();
        case "type":
          return typeName(o);
        case "version":
          return o.latest.revision.padStart(4, " ") + String(o.latest.iteration).padStart(6, "0");
        case "state":
          return stateLabel(o.latest.state);
        case "modified":
          return o.latest.checkedInAt ?? "";
        default:
          return o.checkedOutBy ?? "";
      }
    };
    const rows = [...objects].sort((a, b) => key(a).localeCompare(key(b), undefined, { numeric: true }));
    return sort.desc ? rows.reverse() : rows;
  }, [objects, sort, types]);

  const header = (column: Column) => {
    const active = sort.column === column;
    return (
      <th key={column} aria-sort={active ? (sort.desc ? "descending" : "ascending") : undefined}>
        <button className="sort-btn" onClick={() => setSort({ column, desc: active ? !sort.desc : false })}>
          {LABELS[column]}
          {active && (sort.desc ? <ArrowDown size={12} aria-hidden /> : <ArrowUp size={12} aria-hidden />)}
        </button>
      </th>
    );
  };

  return (
    <>
      <div className="grid-wrap">
        <table className="grid" aria-label={label}>
          <thead>
            <tr>
              <th className="icon-cell"><span className="sr-only">Icon</span></th>
              {columns.map(header)}
            </tr>
          </thead>
          <tbody>
            {sorted.map((o) => (
              <tr key={o.id}>
                <td className="icon-cell">
                  <TypeIcon typeId={o.typeId} />
                </td>
                <td className="nowrap">
                  <Link className="num-link" to={`/objects/${o.id}`}>
                    {o.number}
                  </Link>
                </td>
                <td>
                  <Link className="name-link" to={`/objects/${o.id}`}>
                    {o.name}
                  </Link>
                </td>
                <td className="nowrap">{typeLabel(types?.find((t) => t.id === o.typeId))}</td>
                <td className="nowrap version">{o.version}</td>
                <td>
                  <StateBadge state={o.latest.state} />
                </td>
                {showContext && (
                  <td className="nowrap">
                    <ContextName id={o.contextId} />
                  </td>
                )}
                <td className="nowrap">{o.checkedOutBy ? <PrincipalName id={o.checkedOutBy} /> : <span className="muted">—</span>}</td>
                <td className="nowrap muted date">{formatDate(o.latest.checkedInAt)}</td>
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
