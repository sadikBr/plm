import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CircleAlert, Save, Trash2 } from "lucide-react";
import { useState } from "react";
import { api, unwrap, type Schemas } from "../api/client";
import { usePermissions, type VersionedObject } from "../api/queries";
import { ErrorBanner, Panel, PrincipalName, formatDate } from "../components/common";
import { t } from "../i18n/i18n";

type Spec = Schemas["VariantSpec"];

export const useVariantSpecs = (productId: string) =>
  useQuery({
    queryKey: ["variantSpecs", productId],
    queryFn: () => unwrap(api.GET("/api/v1/objects/{id}/variant-specs", { params: { path: { id: productId } } })),
  });

/** "MOTOR=HT, BATTERY=L" in the set's names: "High torque, Long range 4 kWh". */
export function choicesText(set: Schemas["OptionSet"], selection: Record<string, string>) {
  return set.options
    .filter((o) => selection[o.code])
    .map((o) => o.choices.find((c) => c.code === selection[o.code])?.name ?? selection[o.code])
    .join(", ");
}

/**
 * A configurable product's variant specifications (roadmap 7.8): named selections checked against
 * its rules. Choosing one shows its structure; the current choices can be saved as a new one.
 */
export function VariantSpecsPanel({
  obj,
  set,
  selection,
  current,
  onOpen,
  extra,
}: {
  obj: VersionedObject;
  set: Schemas["OptionSet"];
  selection: Record<string, string>;
  current?: string;
  onOpen: (s: Spec) => void;
  extra?: (s: Spec) => React.ReactNode;
}) {
  const qc = useQueryClient();
  const { data: specs } = useVariantSpecs(obj.id);
  const { data: permissions } = usePermissions(obj.contextId, obj.typeId);
  const canEdit = !!permissions?.has("MODIFY");
  const [name, setName] = useState("");
  const [released, setReleased] = useState(false);
  const refresh = () => void qc.invalidateQueries({ queryKey: ["variantSpecs", obj.id] });
  const save = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/api/v1/objects/{id}/variant-specs", {
          params: { path: { id: obj.id } },
          body: { name, selection, spec: released ? { type: "STATE", state: "RELEASED" } : { type: "LATEST" } },
        }),
      ),
    onSuccess: (s) => {
      setName("");
      refresh();
      onOpen(s);
    },
  });
  const remove = useMutation({
    mutationFn: (specId: string) => unwrap(api.DELETE("/api/v1/variant-specs/{specId}", { params: { path: { specId } } })),
    onSuccess: refresh,
  });
  return (
    <Panel title={t("Variant specifications")} note={t("Named selections of choices: what customers order and what gets built.")} flush>
      <ErrorBanner error={save.error ?? remove.error} />
      {specs?.length ? (
        <table className="grid" aria-label={t("Variant specifications")}>
          <thead>
            <tr>
              <th>{t("Name")}</th>
              <th>{t("Choices")}</th>
              <th>{t("Rules")}</th>
              <th>{t("Saved")}</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {specs.map((s) => (
              <tr key={s.id} className={s.id === current ? "selected" : ""}>
                <td>
                  <button className="link-btn" onClick={() => onOpen(s)}>
                    <strong>{s.name}</strong>
                  </button>
                  {s.description && <div className="muted small">{s.description}</div>}
                </td>
                <td>{choicesText(set, s.selection)}</td>
                <td>
                  {s.check.valid ? (
                    <span className="tag ok">{t("Valid")}</span>
                  ) : (
                    <span className="tag warn" title={s.check.problems.map((p) => p.message).join("\n")}>
                      <CircleAlert size={12} aria-hidden /> {t("Breaks a rule")}
                    </span>
                  )}
                </td>
                <td className="muted small nowrap">
                  <PrincipalName id={s.updatedBy} /> · {formatDate(s.updatedAt)}
                </td>
                <td className="nowrap">
                  {extra?.(s)}
                  {canEdit && (
                    <button className="icon-btn" aria-label={t("Delete {name}", { name: s.name })} onClick={() => remove.mutate(s.id)}>
                      <Trash2 size={13} />
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <div className="empty small">{t("None yet: make choices below and save them.")}</div>
      )}
      {canEdit && (
        <form
          className="row spec-save"
          onSubmit={(e) => {
            e.preventDefault();
            save.mutate();
          }}
        >
          <input
            aria-label={t("Variant name")}
            placeholder={t("Name these choices, e.g. Long-range high torque")}
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
          <select aria-label={t("Read as")} value={released ? "R" : "L"} onChange={(e) => setReleased(e.target.value === "R")}>
            <option value="L">{t("with the latest versions")}</option>
            <option value="R">{t("with the latest Released versions")}</option>
          </select>
          <button className="btn small" disabled={!name.trim() || !Object.keys(selection).length || save.isPending}>
            <Save size={13} aria-hidden /> {t("Save as a variant specification")}
          </button>
        </form>
      )}
    </Panel>
  );
}
