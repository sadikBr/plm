import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronDown, ChevronRight, Layers, Trash2 } from "lucide-react";
import { CollectorDialog } from "./Collector";
import { FamilyPanel } from "./Family";
import { ModelPartsPanel } from "./Associations";
import { useMemo, useState } from "react";
import { Link } from "react-router";
import { api, unwrap, type Schemas } from "../api/client";
import type { AttributeDefinition, VersionedObject } from "../api/queries";
import { ErrorBanner, Loading, Panel, StateBadge, TypeIcon } from "../components/common";
import { ReferenceInput } from "../components/References";
import { t } from "../i18n/i18n";
import { CAD_TYPES } from "./types";

type Kind = Schemas["CadModel"]["kind"];
type Node = Schemas["CadTreeNode"];
type DepKind = Schemas["CadDependency"]["kind"];

export { CAD_TYPES };

/** Which kind of CAD document a type is, by its lineage. */
export function cadKindOf(types: { id: string; parentId?: string | null }[] | undefined, typeId: string): Kind | undefined {
  let id: string | null | undefined = typeId;
  for (let i = 0; id && i < 20; i++) {
    if (id === CAD_TYPES.PART) return "PART";
    if (id === CAD_TYPES.ASSEMBLY) return "ASSEMBLY";
    if (id === CAD_TYPES.DRAWING) return "DRAWING";
    id = types?.find((x) => x.id === id)?.parentId;
  }
  return undefined;
}

const CAD_REF = {
  name: "model",
  dataType: "REFERENCE",
  referenceTypeId: CAD_TYPES.ROOT,
  labels: { en: "Model" },
} as unknown as AttributeDefinition;

function depLabel(k: DepKind): string {
  switch (k) {
    case "COMPONENT":
      return t("component");
    case "DRAWING":
      return t("shown");
    case "REFERENCE":
      return t("reference");
  }
}

const mm = (v: number) => (Math.abs(v) < 1e-9 ? "0" : Number(v.toFixed(2)).toString());

/** A placement's position, read from its 4x4 matrix (column-major). */
function position(m: number[] | null | undefined): string {
  if (!m) return "—";
  return `${mm(m[12]!)}, ${mm(m[13]!)}, ${mm(m[14]!)}`;
}

/** The CAD tab (roadmap 6.1): a model's structure, placements and where it's used. */
export function CadTab({ obj, kind, mine }: { obj: VersionedObject; kind: Kind; mine: boolean }) {
  const [collecting, setCollecting] = useState(false);
  return (
    <div className="details-stack">
      <div className="row" style={{ gap: 8 }}>
        <button className="btn" onClick={() => setCollecting(true)}>
          <Layers size={14} aria-hidden /> {t("Collect…")}
        </button>
        <span className="muted small">{t("Check out, check in, revise or change state together with what goes with it.")}</span>
      </div>
      {collecting && <CollectorDialog objectId={obj.id} onClose={() => setCollecting(false)} />}
      {kind !== "PART" && <CadStructure obj={obj} kind={kind} mine={mine} />}
      {kind !== "DRAWING" && <ModelPartsPanel obj={obj} />}
      {kind !== "DRAWING" && <FamilyPanel obj={obj} mine={mine} />}
      <CadWhereUsed obj={obj} />
    </div>
  );
}

function CadStructure({ obj, kind, mine }: { obj: VersionedObject; kind: Kind; mine: boolean }) {
  const qc = useQueryClient();
  const key = ["cadTree", obj.id, mine];
  const { data, error, isLoading } = useQuery({
    queryKey: key,
    queryFn: () => unwrap(api.GET("/api/v1/objects/{id}/cad/tree", { params: { path: { id: obj.id }, query: { workingCopy: mine } } })),
  });
  const [closed, setClosed] = useState<Set<number>>(new Set());
  const refresh = () => void qc.invalidateQueries({ queryKey: ["cadTree", obj.id] });
  const remove = useMutation({
    mutationFn: (dependencyId: string) =>
      unwrap(api.DELETE("/api/v1/objects/{id}/cad/dependencies/{dependencyId}", { params: { path: { id: obj.id, dependencyId } } })),
    onSuccess: refresh,
  });
  // Rows hidden under a collapsed one.
  const rows = useMemo(() => {
    const out: { node: Node; index: number; hasChildren: boolean }[] = [];
    const nodes = data ?? [];
    let hideBelow: number | null = null;
    nodes.forEach((n, i) => {
      if (hideBelow !== null && n.depth > hideBelow) return;
      hideBelow = null;
      const hasChildren = (nodes[i + 1]?.depth ?? 0) > n.depth;
      out.push({ node: n, index: i, hasChildren });
      if (closed.has(i)) hideBelow = n.depth;
    });
    return out;
  }, [data, closed]);
  const toggle = (i: number) => setClosed((s) => (s.has(i) ? new Set([...s].filter((x) => x !== i)) : new Set([...s, i])));
  const count = (data?.length ?? 1) - 1;
  return (
    <Panel
      title={kind === "DRAWING" ? t("Models shown") : t("Model structure")}
      note={
        mine
          ? t("Your working copy: what you change here is kept at check-in.")
          : t("{n} lines, each model at its latest version.", { n: count })
      }
      flush
    >
      <ErrorBanner error={error ?? remove.error} />
      {isLoading ? (
        <Loading />
      ) : (
        <table className="grid cad-tree" aria-label={t("Model structure")}>
          <thead>
            <tr>
              <th>{t("Model")}</th>
              <th>{t("Occurrence")}</th>
              <th>{t("As")}</th>
              <th>{t("Version")}</th>
              <th>{t("State")}</th>
              <th className="num">{t("Position (mm)")}</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {rows.map(({ node: n, index, hasChildren }) => (
              <tr key={index} className={n.depth === 0 ? "root" : ""}>
                <td>
                  <span className="tree-cell" style={{ paddingLeft: n.depth * 18 }}>
                    {hasChildren ? (
                      <button
                        className="icon-btn tree-toggle"
                        aria-label={closed.has(index) ? t("Expand") : t("Collapse")}
                        onClick={() => toggle(index)}
                      >
                        {closed.has(index) ? <ChevronRight size={13} /> : <ChevronDown size={13} />}
                      </button>
                    ) : (
                      <span className="tree-toggle" />
                    )}
                    {n.model.readable ? (
                      <>
                        {n.model.typeId && <TypeIcon typeId={n.model.typeId} />}{" "}
                        {n.depth === 0 ? (
                          <strong>{n.model.number}</strong>
                        ) : (
                          <Link to={`/objects/${n.model.id}?tab=cad`}>{n.model.number}</Link>
                        )}{" "}
                        {n.model.name}
                      </>
                    ) : (
                      <span className="muted">{t("(no access)")}</span>
                    )}
                  </span>
                </td>
                <td className="mono">{n.dependency?.occurrence ?? ""}</td>
                <td>
                  {n.dependency && (
                    <span className="tag">
                      {depLabel(n.dependency.kind)}
                      {!n.dependency.required && ` · ${t("optional")}`}
                    </span>
                  )}
                </td>
                <td>{n.model.version}</td>
                <td>{n.model.state && <StateBadge state={n.model.state} />}</td>
                <td className="num mono">{n.depth === 0 ? "" : position(n.worldTransform)}</td>
                <td className="row-actions">
                  {mine && n.depth === 1 && n.dependency && (
                    <button className="btn ghost small" aria-label={t("Remove")} onClick={() => remove.mutate(n.dependency!.id)}>
                      <Trash2 size={13} aria-hidden />
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {!isLoading && count === 0 && (
        <div className="empty small">{kind === "DRAWING" ? t("It shows no model yet.") : t("No components yet.")}</div>
      )}
      {mine && <AddDependencyForm objectId={obj.id} kind={kind} onAdded={refresh} />}
    </Panel>
  );
}

function AddDependencyForm({ objectId, kind, onAdded }: { objectId: string; kind: Kind; onAdded: () => void }) {
  const [childId, setChildId] = useState("");
  const [occurrence, setOccurrence] = useState("");
  const [x, setX] = useState("0");
  const [y, setY] = useState("0");
  const [z, setZ] = useState("0");
  const depKind: DepKind = kind === "DRAWING" ? "DRAWING" : "COMPONENT";
  const add = useMutation({
    mutationFn: () => {
      const n = (v: string) => Number(v.replace(",", ".")) || 0;
      const transform = depKind === "COMPONENT" ? [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, n(x), n(y), n(z), 1] : null;
      return unwrap(
        api.POST("/api/v1/objects/{id}/cad/dependencies", {
          params: { path: { id: objectId } },
          body: { childId, kind: depKind, required: true, occurrence: occurrence.trim() || null, transform },
        }),
      );
    },
    onSuccess: () => {
      setChildId("");
      setOccurrence("");
      onAdded();
    },
  });
  return (
    <div className="inline-form cad-add">
      <div className="attr-form cols-3">
        <div className="field wide">
          <label htmlFor="cad-child">{depKind === "DRAWING" ? t("Model it shows") : t("Component")}</label>
          <ReferenceInput id="cad-child" def={CAD_REF} value={childId || undefined} onChange={(v) => setChildId(v ? String(v) : "")} />
        </div>
        {depKind === "COMPONENT" && (
          <>
            <div className="field">
              <label htmlFor="cad-occ">{t("Occurrence")}</label>
              <input id="cad-occ" value={occurrence} placeholder="BOLT:1" onChange={(e) => setOccurrence(e.target.value)} />
            </div>
            <div className="field">
              <label>{t("Position x, y, z (mm)")}</label>
              <div className="row" style={{ gap: 4 }}>
                <input aria-label="x" inputMode="decimal" value={x} onChange={(e) => setX(e.target.value)} />
                <input aria-label="y" inputMode="decimal" value={y} onChange={(e) => setY(e.target.value)} />
                <input aria-label="z" inputMode="decimal" value={z} onChange={(e) => setZ(e.target.value)} />
              </div>
            </div>
          </>
        )}
        <div className="field">
          <button className="btn primary small" disabled={!childId || add.isPending} onClick={() => add.mutate()}>
            {depKind === "DRAWING" ? t("Add the model") : t("Add the component")}
          </button>
        </div>
      </div>
      <ErrorBanner error={add.error} />
    </div>
  );
}

function CadWhereUsed({ obj }: { obj: VersionedObject }) {
  const { data, isLoading } = useQuery({
    queryKey: ["cadWhereUsed", obj.id],
    queryFn: () => unwrap(api.GET("/api/v1/objects/{id}/cad/where-used", { params: { path: { id: obj.id } } })),
  });
  return (
    <Panel title={t("Used in")} note={t("The assemblies and drawings whose versions depend on it.")} flush>
      {isLoading ? (
        <Loading />
      ) : !data?.length ? (
        <div className="empty small">{t("Nothing depends on it.")}</div>
      ) : (
        <table className="grid" aria-label={t("Used in")}>
          <thead>
            <tr>
              <th>{t("Model")}</th>
              <th>{t("Version")}</th>
              <th>{t("As")}</th>
              <th>{t("Occurrence")}</th>
            </tr>
          </thead>
          <tbody>
            {data.map((u) => (
              <tr key={u.dependencyId}>
                <td>
                  {u.parent.typeId && <TypeIcon typeId={u.parent.typeId} />}{" "}
                  <Link to={`/objects/${u.parent.id}?tab=cad`}>{u.parent.number}</Link> {u.parent.name}
                </td>
                <td>
                  {u.parentVersion} {u.latest ? <span className="tag accent">{t("latest")}</span> : null}
                </td>
                <td>{depLabel(u.kind)}</td>
                <td className="mono">{u.occurrence ?? ""}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Panel>
  );
}
