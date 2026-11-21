import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "react-router";
import { X } from "lucide-react";
import { api, unwrap } from "../api/client";
import { useObjectSearch, useTypeAttributes, useTypes, type AttributeDefinition, type VersionedObject } from "../api/queries";
import { t } from "../i18n/i18n";
import { pickLabel } from "./AttributeForm";
import { Panel, StateBadge } from "./common";

/** Reference attributes (roadmap 4.12): what they point to, how to pick it, who points here. */

const isId = (v: unknown): v is string => typeof v === "string" && /^[0-9a-f-]{36}$/i.test(v);

/** The objects [ids] point to (those the user can read), by id. */
export function useReferenced(ids: unknown[]) {
  const wanted = [...new Set(ids.flat().filter(isId))].sort();
  return useQuery({
    queryKey: ["objects", "byIds", wanted],
    queryFn: () => unwrap(api.GET("/api/v1/objects", { params: { query: { ids: wanted, limit: 200 } } })),
    enabled: wanted.length > 0,
    staleTime: 10_000,
    select: (list) => new Map(list.map((o) => [o.id, o])),
  });
}

/** "number — name" of a referenced object, for tables and exports. */
export const referenceText = (o: VersionedObject | undefined, id: unknown) =>
  o ? `${o.number} — ${o.name}` : isId(id) ? t("Not visible") : "";

/** A link to the referenced object. */
export function ObjectRef({ id }: { id: unknown }) {
  const { data } = useReferenced([id]);
  if (!isId(id)) return <>—</>;
  const o = data?.get(id);
  if (!o) return <span className="muted">{t("Not visible")}</span>;
  return (
    <Link to={`/objects/${o.id}`} className="object-ref">
      <span className="mono">{o.number}</span> — {o.name}
    </Link>
  );
}

/** Whether [typeId] is [wanted] or one of its subtypes. */
function useIsA() {
  const { data: types } = useTypes();
  return (typeId: string, wanted: string | null | undefined) => {
    if (!wanted) return true;
    for (let cur = types?.find((ty) => ty.id === typeId); cur; cur = types?.find((ty) => ty.id === cur!.parentId)) {
      if (cur.id === wanted) return true;
    }
    return false;
  };
}

/** Picks the object a reference attribute points to: search by number or name, limited to its type. */
export function ReferenceInput({
  id,
  def,
  value,
  onChange,
  invalid,
}: {
  id: string;
  def: AttributeDefinition;
  value: unknown;
  onChange: (v: unknown) => void;
  invalid?: boolean;
}) {
  const [text, setText] = useState("");
  const isA = useIsA();
  const { data: types } = useTypes();
  const search = useObjectSearch(text, 8);
  const matches = (search.data?.pages.flat() ?? []).filter((o) => isA(o.typeId, def.referenceTypeId));
  const target = types?.find((ty) => ty.id === def.referenceTypeId);
  if (isId(value)) {
    return (
      <div className="reference-value" id={id}>
        <ObjectRef id={value} />
        {!def.required && (
          <button type="button" className="btn small icon" aria-label={t("Clear")} onClick={() => onChange(undefined)}>
            <X size={12} aria-hidden />
          </button>
        )}
        <button type="button" className="btn small" onClick={() => onChange(undefined)}>
          {t("Change")}
        </button>
      </div>
    );
  }
  return (
    <div className="reference-pick">
      <input
        id={id}
        aria-invalid={invalid || undefined}
        value={text}
        onChange={(e) => setText(e.target.value)}
        placeholder={target ? t("Find a {type} by number or name", { type: target.displayName }) : t("Find an object by number or name")}
        autoComplete="off"
      />
      {text.trim() && (
        <ul className="pick-list" role="listbox" aria-label={t("Matches")}>
          {matches.length === 0 && !search.isFetching && <li className="muted">{t("Nothing found")}</li>}
          {matches.map((o) => (
            <li key={o.id}>
              <button
                type="button"
                role="option"
                aria-selected={false}
                onClick={() => {
                  onChange(o.id);
                  setText("");
                }}
              >
                <span className="mono">{o.number}</span> — {o.name}
                <span className="muted"> · {types?.find((ty) => ty.id === o.typeId)?.displayName}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** The objects whose reference attributes point to [objectId]. Renders nothing when there are none. */
export function ReferencedBy({ objectId }: { objectId: string }) {
  const { data } = useQuery({
    queryKey: ["object", objectId, "referenced-by"],
    queryFn: () => unwrap(api.GET("/api/v1/objects/{id}/referenced-by", { params: { path: { id: objectId } } })),
  });
  const { data: types } = useTypes();
  if (!data?.length) return null;
  return (
    <Panel title={t("Referenced by")} flush className="referenced-by">
      <table className="grid" aria-label={t("Referenced by")}>
        <thead>
          <tr>
            <th>{t("Number")}</th>
            <th>{t("Name")}</th>
            <th>{t("Type")}</th>
            <th>{t("Version")}</th>
            <th>{t("State")}</th>
            <th>{t("Through")}</th>
          </tr>
        </thead>
        <tbody>
          {data.map((r) => (
            <tr key={`${r.id}:${r.attribute}`}>
              <td className="mono">
                <Link to={`/objects/${r.id}`}>{r.number}</Link>
              </td>
              <td>{r.name}</td>
              <td>{types?.find((ty) => ty.id === r.typeId)?.displayName}</td>
              <td>{r.version}</td>
              <td>
                <StateBadge state={r.state} />
              </td>
              <td>
                <ReferenceAttributeLabel typeId={r.typeId} name={r.attribute} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </Panel>
  );
}

function ReferenceAttributeLabel({ typeId, name }: { typeId: string; name: string }) {
  const { data } = useTypeAttributes(typeId);
  const def = data?.find((d) => d.name === name);
  return <>{def ? pickLabel(def.labels, name) : name}</>;
}
