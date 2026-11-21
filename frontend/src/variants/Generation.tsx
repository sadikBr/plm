import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { GitFork, Wand2 } from "lucide-react";
import { Link } from "react-router";
import { api, unwrap, type Schemas } from "../api/client";
import type { VersionedObject } from "../api/queries";
import { ErrorBanner, Panel, PrincipalName, formatDate } from "../components/common";
import { t } from "../i18n/i18n";

/** Generates the parts a variant specification builds (roadmap 7.9). */
export function useGenerate(productId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (specId: string) => unwrap(api.POST("/api/v1/variant-specs/{specId}/generate", { params: { path: { specId } } })),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["variants", productId] }),
  });
}

export function GenerateButton({ spec, generate }: { spec: Schemas["VariantSpec"]; generate: ReturnType<typeof useGenerate> }) {
  return (
    <button className="btn small ghost" disabled={!spec.check.valid || generate.isPending} onClick={() => generate.mutate(spec.id)}>
      <Wand2 size={13} aria-hidden /> {t("Generate")}
    </button>
  );
}

/** What generation did: parts made, reused, or used as they are. */
export function GenerationReport({ result }: { result: Schemas["GenerationResult"] }) {
  const outcome = (o: string) => (o === "CREATED" ? t("made") : o === "REUSED" ? t("reused") : t("as is"));
  return (
    <Panel
      title={t("Generated {name}", { name: result.top.name })}
      note={t("{created} made, {reused} reused.", { created: result.created, reused: result.reused })}
      flush
    >
      <table className="grid" aria-label={t("Generated parts")}>
        <thead>
          <tr>
            <th>{t("Part")}</th>
            <th>{t("Variant of")}</th>
            <th>{t("Outcome")}</th>
          </tr>
        </thead>
        <tbody>
          {result.parts
            .filter((p) => p.outcome !== "AS_IS")
            .map((p) => (
              <tr key={p.id}>
                <td>
                  <Link to={`/objects/${p.id}?tab=structure`}>{p.number}</Link> {p.name}
                </td>
                <td>{p.baseNumber}</td>
                <td>
                  <span className={`tag ${p.outcome === "CREATED" ? "ok" : ""}`}>{outcome(p.outcome)}</span>
                </td>
              </tr>
            ))}
        </tbody>
      </table>
    </Panel>
  );
}

/** Where a generated variant came from, and the variants generated from a part (roadmap 7.9). */
export function VariantOriginPanels({ obj }: { obj: VersionedObject }) {
  const { data: origin } = useQuery({
    queryKey: ["variantOrigin", obj.id],
    queryFn: () => unwrap(api.GET("/api/v1/objects/{id}/variant-origin", { params: { path: { id: obj.id } } })),
  });
  const { data: variants, error } = useQuery({
    queryKey: ["variants", obj.id],
    queryFn: () => unwrap(api.GET("/api/v1/objects/{id}/variants", { params: { path: { id: obj.id } } })),
  });
  const choices = (s: Record<string, string>) =>
    Object.entries(s)
      .map(([k, v]) => `${k}=${v}`)
      .join(", ");
  return (
    <>
      <ErrorBanner error={error} />
      {origin && (
        <Panel title={t("Generated variant")} note={t("Made by variant generation: its structure is what the choices build.")}>
          <p style={{ margin: 0 }}>
            <GitFork size={14} aria-hidden />{" "}
            {t("A variant of {base} at {version}", {
              base: `${origin.baseNumber ?? ""} ${origin.baseName ?? ""}`.trim(),
              version: origin.baseVersion ?? "",
            })}{" "}
            · <Link to={`/objects/${origin.baseId}?tab=options`}>{t("Open the base")}</Link>
          </p>
          <p className="muted small" style={{ marginBottom: 0 }}>
            {t("For {choices}", { choices: choices(origin.selection) || t("its choices") })}
            {origin.specName && <> · {t("specification {name}", { name: origin.specName })}</>} · <PrincipalName id={origin.createdBy} /> ·{" "}
            {formatDate(origin.createdAt)}
          </p>
        </Panel>
      )}
      {variants && variants.length > 0 && (
        <Panel
          title={t("Generated variants")}
          note={t("Parts generated from this one, reused by later generations with the same choices.")}
          flush
        >
          <table className="grid" aria-label={t("Generated variants")}>
            <thead>
              <tr>
                <th>{t("Variant")}</th>
                <th>{t("From version")}</th>
                <th>{t("Choices")}</th>
                <th>{t("Specification")}</th>
                <th>{t("Made")}</th>
              </tr>
            </thead>
            <tbody>
              {variants.map((v) => (
                <tr key={v.variantId}>
                  <td>
                    <Link to={`/objects/${v.variantId}?tab=structure`}>{t("Open")}</Link>
                  </td>
                  <td>{v.baseVersion}</td>
                  <td>
                    <code>{choices(v.selection)}</code>
                  </td>
                  <td>{v.specName ?? <span className="muted">—</span>}</td>
                  <td className="muted small">{formatDate(v.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Panel>
      )}
    </>
  );
}
