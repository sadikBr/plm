import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Hammer, Trash2 } from "lucide-react";
import { useState } from "react";
import { Link } from "react-router";
import { api, unwrap, type Schemas } from "../api/client";
import type { VersionedObject } from "../api/queries";
import { ErrorBanner, Panel, StateBadge, TypeIcon } from "../components/common";
import { ReferenceInput } from "../components/References";
import { t } from "../i18n/i18n";
import { CAD_TYPES } from "./types";
import type { AttributeDefinition } from "../api/queries";

type Kind = Schemas["CadAssociation"]["kind"];

const CAD_REF = {
  name: "model",
  dataType: "REFERENCE",
  referenceTypeId: CAD_TYPES.ROOT,
  labels: { en: "Model" },
} as unknown as AttributeDefinition;

const kindLabel = (k: Kind) => (k === "OWNER" ? t("owning model") : t("contributing"));

function changeLabel(c: string): string {
  switch (c) {
    case "ADDED":
      return t("added");
    case "UPDATED":
      return t("quantity changed");
    case "UNCHANGED":
      return t("unchanged");
    case "REMOVED":
      return t("removed");
    default:
      return t("kept (added by hand)");
  }
}

/** On a part (roadmap 6.4): its CAD models, and building its structure from the owning one. */
export function PartCadPanel({ obj, mine }: { obj: VersionedObject; mine: boolean }) {
  const qc = useQueryClient();
  const key = ["cadAssociations", obj.id, mine];
  const { data } = useQuery({
    queryKey: key,
    queryFn: () =>
      unwrap(api.GET("/api/v1/objects/{id}/cad/associations", { params: { path: { id: obj.id }, query: { workingCopy: mine } } })),
  });
  const [cadId, setCadId] = useState("");
  const [kind, setKind] = useState<Kind>("OWNER");
  const [createParts, setCreateParts] = useState(false);
  const refresh = () => {
    void qc.invalidateQueries({ queryKey: ["cadAssociations", obj.id] });
    void qc.invalidateQueries({ queryKey: ["structure"] });
    void qc.invalidateQueries({ queryKey: ["usages"] });
  };
  const add = useMutation({
    mutationFn: () =>
      unwrap(api.POST("/api/v1/objects/{id}/cad/associations", { params: { path: { id: obj.id } }, body: { cadId, kind } })),
    onSuccess: () => {
      setCadId("");
      refresh();
    },
  });
  const remove = useMutation({
    mutationFn: (associationId: string) =>
      unwrap(api.DELETE("/api/v1/objects/{id}/cad/associations/{associationId}", { params: { path: { id: obj.id, associationId } } })),
    onSuccess: refresh,
  });
  const build = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/objects/{id}/cad/build", { params: { path: { id: obj.id } }, body: { createParts } })),
    onSuccess: () => {
      refresh();
      void qc.invalidateQueries();
    },
  });
  if (!data) return null;
  if (!data.length && !mine) return null;
  const owner = data.find((a) => a.kind === "OWNER");
  return (
    <Panel
      title={t("CAD models")}
      note={
        owner
          ? t("Its structure can be built from its owning model.")
          : t("Associate the model this part is, to build its structure from it.")
      }
      actions={
        mine &&
        owner && (
          <>
            <label className="check small">
              <input type="checkbox" checked={createParts} onChange={(e) => setCreateParts(e.target.checked)} />
              {t("Make parts for components without one")}
            </label>
            <button className="btn small primary" disabled={build.isPending} onClick={() => build.mutate()}>
              <Hammer size={13} aria-hidden /> {t("Build from CAD")}
            </button>
          </>
        )
      }
    >
      {data.length > 0 && (
        <ul className="cad-assoc-list">
          {data.map((a) => (
            <li key={a.id}>
              <span className={a.kind === "OWNER" ? "tag accent" : "tag"}>{kindLabel(a.kind)}</span>{" "}
              {a.model.typeId && <TypeIcon typeId={a.model.typeId} />} <Link to={`/objects/${a.model.id}?tab=cad`}>{a.model.number}</Link>{" "}
              {a.model.name} <span className="muted">{a.model.version}</span> {a.model.state && <StateBadge state={a.model.state} />}
              {mine && (
                <button className="btn ghost small" aria-label={t("Remove")} onClick={() => remove.mutate(a.id)}>
                  <Trash2 size={13} aria-hidden />
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
      {mine && (
        <div className="row" style={{ gap: 8, alignItems: "flex-end", marginTop: 8 }}>
          <div style={{ flex: 1 }}>
            <ReferenceInput id="assoc-model" def={CAD_REF} value={cadId || undefined} onChange={(v) => setCadId(v ? String(v) : "")} />
          </div>
          <select aria-label={t("As")} style={{ width: "auto" }} value={kind} onChange={(e) => setKind(e.target.value as Kind)}>
            <option value="OWNER">{t("owning model")}</option>
            <option value="CONTRIBUTING">{t("contributing")}</option>
          </select>
          <button className="btn small" disabled={!cadId || add.isPending} onClick={() => add.mutate()}>
            {t("Associate")}
          </button>
        </div>
      )}
      <ErrorBanner error={add.error ?? remove.error ?? build.error} />
      {build.data && <BuildReport result={build.data} />}
    </Panel>
  );
}

function BuildReport({ result }: { result: Schemas["BuildResult"] }) {
  return (
    <div className="build-report">
      <p>
        <strong>{t("Built from {model}:", { model: `${result.model.number} ${result.model.name}` })}</strong>
      </p>
      <table className="grid" aria-label={t("Build result")}>
        <thead>
          <tr>
            <th>{t("Part")}</th>
            <th className="num">{t("Quantity")}</th>
            <th>{t("What happened")}</th>
          </tr>
        </thead>
        <tbody>
          {result.lines.map((l) => (
            <tr key={l.part.id}>
              <td>
                <Link to={`/objects/${l.part.id}`}>{l.part.number}</Link> {l.part.name}
              </td>
              <td className="num">{l.quantity}</td>
              <td>
                <span className={`tag ${l.change === "ADDED" ? "allow" : l.change === "REMOVED" ? "deny" : ""}`}>
                  {changeLabel(l.change)}
                </span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {result.created.length > 0 && (
        <p className="muted">{t("Parts made: {parts}", { parts: result.created.map((p) => p.number).join(", ") })}</p>
      )}
      {result.missing.length > 0 && (
        <div className="banner warn">
          {t("These components have no part, so they aren't in the structure: {models}", {
            models: result.missing.map((m) => `${m.number} ${m.name}`).join(", "),
          })}
        </div>
      )}
    </div>
  );
}

/** On a CAD document: the parts it describes. */
export function ModelPartsPanel({ obj }: { obj: VersionedObject }) {
  const { data } = useQuery({
    queryKey: ["cadAssociations", obj.id, false],
    queryFn: () => unwrap(api.GET("/api/v1/objects/{id}/cad/associations", { params: { path: { id: obj.id } } })),
  });
  if (!data?.length) return null;
  return (
    <Panel title={t("Parts")} note={t("The parts this model describes.")}>
      <ul className="cad-assoc-list">
        {data.map((a) => (
          <li key={a.id}>
            <span className={a.kind === "OWNER" ? "tag accent" : "tag"}>{kindLabel(a.kind)}</span>{" "}
            <Link to={`/objects/${a.part.id}?tab=structure`}>{a.part.number}</Link> {a.part.name}{" "}
            <span className="muted">{a.part.version}</span> {a.part.state && <StateBadge state={a.part.state} />}
          </li>
        ))}
      </ul>
    </Panel>
  );
}
