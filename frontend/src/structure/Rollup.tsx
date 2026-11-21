import { useQuery } from "@tanstack/react-query";
import { AlertTriangle, Lock } from "lucide-react";
import { useState } from "react";
import { Link } from "react-router";
import { api, unwrap } from "../api/client";
import type { VersionedObject } from "../api/queries";
import { pickLabel } from "../components/AttributeForm";
import { ErrorBanner, Loading, StateBadge, TypeIcon } from "../components/common";
import { locale, t, tn } from "../i18n/i18n";
import { useUnits } from "./Structure";
import type { Spec } from "./StructureTree";

// Roll-ups and the flattened structure (roadmap 4.11).

const num = (n: number | null | undefined) => (n == null ? "" : n.toLocaleString(locale(), { maximumFractionDigits: 4 }));

function specQuery(spec: Spec) {
  return {
    spec: spec.type === "AS_OF" ? "LATEST" : spec.type,
    ...(spec.type === "STATE" ? { state: spec.state } : {}),
    ...(spec.type === "BASELINE" ? { baselineId: spec.baselineId } : {}),
  };
}

/** Every part once, with its total quantity across all lines and levels. */
export function FlattenedView({ obj, spec }: { obj: VersionedObject; spec: Spec }) {
  const units = useUnits();
  const unitLabel = (k: string) => units.find((u) => u.key === k)?.label ?? k;
  const { data, error, isLoading } = useQuery({
    queryKey: ["flattened", obj.id, spec],
    queryFn: () => unwrap(api.GET("/api/v1/objects/{id}/flattened", { params: { path: { id: obj.id }, query: specQuery(spec) as never } })),
  });
  if (isLoading) return <Loading />;
  return (
    <>
      <ErrorBanner error={error} />
      <div className="tree-bar muted small">{tn("{n} different part", "{n} different parts", data?.length ?? 0)}</div>
      <div className="grid-wrap">
        <table className="grid structure" aria-label={t("Flattened structure")}>
          <thead>
            <tr>
              <th className="icon-cell" />
              <th>{t("Number")}</th>
              <th>{t("Name")}</th>
              <th>{t("Version")}</th>
              <th>{t("State")}</th>
              <th className="num">{t("Total quantity")}</th>
              <th>{t("Unit")}</th>
              <th className="num">{t("Lines")}</th>
              <th>{t("Levels")}</th>
            </tr>
          </thead>
          <tbody>
            {data?.map((f) => (
              <tr key={`${f.part.id}:${f.unit}`}>
                <td className="icon-cell">
                  {f.part.typeId ? <TypeIcon typeId={f.part.typeId} /> : <Lock size={14} className="icon-muted" />}
                </td>
                <td className="nowrap">
                  {f.part.readable ? (
                    <Link className="num-link" to={`/objects/${f.part.id}`}>
                      {f.part.number}
                    </Link>
                  ) : (
                    t("(no access)")
                  )}
                </td>
                <td>{f.part.name}</td>
                <td className="version">{f.part.version}</td>
                <td>{f.part.state && <StateBadge state={f.part.state} />}</td>
                <td className="num">
                  <strong>{num(f.totalQuantity)}</strong>
                </td>
                <td>{unitLabel(f.unit)}</td>
                <td className="num muted">{f.lines}</td>
                <td className="muted">{f.levels.join(", ")}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}

/** A numeric attribute summed up the tree, for one piece of the top. */
export function RollupView({ obj, spec }: { obj: VersionedObject; spec: Spec }) {
  const { data: attributes } = useQuery({
    queryKey: ["rollupAttributes", obj.id],
    queryFn: () => unwrap(api.GET("/api/v1/objects/{id}/rollup-attributes", { params: { path: { id: obj.id } } })),
  });
  const [chosen, setChosen] = useState<string | null>(null);
  const attribute = chosen ?? attributes?.find((a) => a.name === "mass")?.name ?? attributes?.[0]?.name ?? "";
  const { data, error, isLoading } = useQuery({
    queryKey: ["rollup", obj.id, attribute, spec],
    queryFn: () =>
      unwrap(
        api.GET("/api/v1/objects/{id}/rollup", { params: { path: { id: obj.id }, query: { attribute, ...specQuery(spec) } as never } }),
      ),
    enabled: !!attribute,
  });
  const unit = data?.unit ? ` ${data.unit}` : "";
  return (
    <>
      <div className="tree-bar">
        <label className="row small" style={{ gap: 6 }}>
          {t("Sum")}
          <select aria-label={t("Attribute to sum")} value={attribute} onChange={(e) => setChosen(e.target.value)}>
            {attributes?.map((a) => (
              <option key={a.name} value={a.name}>
                {pickLabel(a.labels, a.name)}
              </option>
            ))}
          </select>
        </label>
        {data && (
          <span className="rollup-total" aria-label={t("Total")}>
            {t("Total for one {number}:", { number: obj.number })}{" "}
            <strong>
              {num(data.total)}
              {unit}
            </strong>
          </span>
        )}
        {!!data?.missing && (
          <span className="tag warn">
            <AlertTriangle size={11} aria-hidden /> {tn("{n} part has no value", "{n} parts have no value", data.missing)}
          </span>
        )}
      </div>
      <ErrorBanner error={error} />
      {!attribute && <p className="empty">{t("This part has no numeric attribute to sum.")}</p>}
      {isLoading && !!attribute ? (
        <Loading />
      ) : (
        data && (
          <div className="grid-wrap">
            <table className="grid structure" aria-label={t("Roll-up")}>
              <thead>
                <tr>
                  <th>{t("Number")}</th>
                  <th>{t("Name")}</th>
                  <th className="num">{t("Quantity in the top")}</th>
                  <th className="num">{t("Own value")}</th>
                  <th className="num">{t("Per piece")}</th>
                  <th className="num">{t("Line total")}</th>
                </tr>
              </thead>
              <tbody>
                {data.nodes.map((n) => (
                  <tr key={n.path} className={n.missing ? "missing" : undefined}>
                    <td className="nowrap" style={{ paddingLeft: 8 + (n.level - 1) * 18 }}>
                      {n.part.typeId && <TypeIcon typeId={n.part.typeId} />}{" "}
                      {n.part.readable ? (
                        <Link className="num-link" to={`/objects/${n.part.id}`}>
                          {n.part.number}
                        </Link>
                      ) : (
                        t("(no access)")
                      )}
                    </td>
                    <td>
                      {n.part.name}
                      {n.missing && <span className="tag warn small-tag">{t("no value")}</span>}
                    </td>
                    <td className="num muted">{num(n.extendedQuantity)}</td>
                    <td className="num">{num(n.ownValue)}</td>
                    <td className="num">{num(n.rolledValue)}</td>
                    <td className="num">
                      <strong>{num(n.lineTotal)}</strong>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )
      )}
    </>
  );
}
