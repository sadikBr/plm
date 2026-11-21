import { useQuery } from "@tanstack/react-query";
import { FileSpreadsheet, Flag, Lock } from "lucide-react";
import { useState } from "react";
import { Link } from "react-router";
import { api, unwrap } from "../api/client";
import type { VersionedObject } from "../api/queries";
import { ErrorBanner, Loading, Panel, StateBadge, TypeIcon, stateLabel } from "../components/common";
import { locale, t, tn } from "../i18n/i18n";
import { useUnits } from "./Structure";
import { SpecPicker, effectivityQuery, useDefaultSpec, type Spec } from "./StructureTree";
import { ReplaceEverywhereButton } from "./StructureOperations";
import { BulkProgress } from "../pages/Bulk";

const qty = (n: number) => n.toLocaleString(locale(), { maximumFractionDigits: 6 });

/** Where a part is used (roadmap 4.6): the parts using it, and up to the end items. */
export function WhereUsedTab({ obj }: { obj: VersionedObject }) {
  const preferred = useDefaultSpec(obj.contextId);
  const [chosen, setChosen] = useState<Spec | null>(null);
  const [allLevels, setAllLevels] = useState(false);
  const [job, setJob] = useState<string | null>(null);
  const spec = chosen ?? preferred;
  const units = useUnits();
  const unitLabel = (key: string) => units.find((u) => u.key === key)?.label ?? key;
  const query = {
    spec: spec.type,
    depth: allLevels ? 50 : 1,
    ...(spec.type === "STATE" ? { state: spec.state ?? "RELEASED" } : {}),
    ...(spec.type === "AS_OF" && spec.asOf ? { asOf: new Date(`${spec.asOf}T23:59:59`).toISOString() } : {}),
    ...effectivityQuery(spec),
  };
  const { data, error, isLoading } = useQuery({
    queryKey: ["whereUsed", obj.id, query],
    queryFn: () => unwrap(api.GET("/api/v1/objects/{id}/where-used", { params: { path: { id: obj.id }, query } })),
  });
  const ends = data?.nodes.filter((n) => n.top).length ?? 0;
  const substituteUses = useQuery({
    queryKey: ["substituteUses", obj.id],
    queryFn: () => unwrap(api.GET("/api/v1/objects/{id}/substitute-uses", { params: { path: { id: obj.id } } })),
  });

  const exportRows = async () => {
    if (!data) return;
    const res = await api.POST("/api/v1/export/xlsx", {
      body: {
        sheetName: t("Where used"),
        columns: [t("Level"), t("Number"), t("Name"), t("Version"), t("State"), t("Line"), t("Quantity"), t("Unit"), t("End item")],
        rows: data.nodes.map((n) => [
          n.level,
          n.parent.number ?? t("(no access)"),
          n.parent.name ?? "",
          n.parent.version ?? "",
          n.parent.state ? stateLabel(n.parent.state) : "",
          n.lineNumber,
          n.quantity,
          unitLabel(n.unit),
          n.top ? t("Yes") : "",
        ]),
      },
      parseAs: "blob",
      headers: { Accept: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" },
    });
    if (!res.data) return;
    const url = URL.createObjectURL(res.data as Blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `${obj.number} where used.xlsx`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 10_000);
  };

  return (
    <div className="details-stack">
      <Panel title={t("Where used")} actions={<SpecPicker spec={spec} onChange={setChosen} />} flush>
        <div className="tree-bar">
          <span className="muted small">
            {data &&
              (allLevels
                ? `${tn("{n} line", "{n} lines", data.nodes.length)} · ${tn("{n} end item", "{n} end items", ends)}`
                : tn("Used directly in {n} part", "Used directly in {n} parts", data.nodes.length))}
          </span>
          <span className="grow" />
          <label className="row small" style={{ gap: 4 }}>
            <input type="checkbox" checked={allLevels} onChange={(e) => setAllLevels(e.target.checked)} />{" "}
            {t("All levels, up to the end items")}
          </label>
          <ReplaceEverywhereButton obj={obj} onStarted={setJob} />
          <button className="btn small" onClick={() => void exportRows()} disabled={!data?.nodes.length}>
            <FileSpreadsheet size={13} aria-hidden /> {t("Export to Excel")}
          </button>
        </div>
        {job && (
          <div className="import-progress">
            <BulkProgress jobId={job} onClose={() => setJob(null)} />
          </div>
        )}
        <ErrorBanner error={error} />
        {isLoading ? (
          <Loading />
        ) : (
          <div className="grid-wrap">
            <table className="grid structure tree" aria-label={t("Where used")}>
              <thead>
                <tr>
                  <th>{t("Number")}</th>
                  <th>{t("Name")}</th>
                  <th>{t("Version")}</th>
                  <th>{t("State")}</th>
                  <th className="num">{t("Line")}</th>
                  <th className="num">{t("Quantity")}</th>
                  <th>{t("Unit")}</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {data?.nodes.map((n) => {
                  const p = n.parent;
                  return (
                    <tr key={n.path} aria-level={n.level}>
                      <td className="nowrap tree-cell" style={{ paddingLeft: 8 + (n.level - 1) * 18 }}>
                        {p.typeId ? <TypeIcon typeId={p.typeId} /> : <Lock size={14} className="icon-muted" />}{" "}
                        {p.readable ? (
                          <Link className="num-link" to={`/objects/${p.id}?tab=where-used`}>
                            {p.number}
                          </Link>
                        ) : (
                          <span className="muted">{t("(no access)")}</span>
                        )}
                      </td>
                      <td>{p.name}</td>
                      <td className="version">{p.version}</td>
                      <td>{p.state && <StateBadge state={p.state} />}</td>
                      <td className="num muted">{n.lineNumber}</td>
                      <td className="num">{qty(n.quantity)}</td>
                      <td>{unitLabel(n.unit)}</td>
                      <td>
                        {n.top && (
                          <span className="tag accent">
                            <Flag size={11} aria-hidden /> {t("End item")}
                          </span>
                        )}
                      </td>
                    </tr>
                  );
                })}
                {data?.nodes.length === 0 && (
                  <tr>
                    <td colSpan={8} className="empty">
                      {t("No part uses this one.")}
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        )}
      </Panel>
      {!!substituteUses.data?.length && (
        <Panel title={t("Also allowed as a substitute in")} flush>
          <table className="grid" aria-label={t("Also allowed as a substitute in")}>
            <thead>
              <tr>
                <th>{t("Used in")}</th>
                <th>{t("Name")}</th>
                <th className="num">{t("Line")}</th>
                <th>{t("Instead of")}</th>
              </tr>
            </thead>
            <tbody>
              {substituteUses.data.map((u) => (
                <tr key={`${u.parent.id}:${u.lineNumber}`}>
                  <td className="nowrap">
                    {u.parent.readable ? (
                      <Link className="num-link" to={`/objects/${u.parent.id}?tab=structure`}>
                        {u.parent.number}
                      </Link>
                    ) : (
                      t("(no access)")
                    )}
                  </td>
                  <td>{u.parent.name}</td>
                  <td className="num">{u.lineNumber}</td>
                  <td>{u.original.readable ? `${u.original.number} ${u.original.name}` : t("(no access)")}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Panel>
      )}
    </div>
  );
}
