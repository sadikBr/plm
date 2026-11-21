import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Lock, Trash2 } from "lucide-react";
import { useState } from "react";
import { Link } from "react-router";
import { api, unwrap, type Schemas } from "../api/client";
import { usePermissions, type VersionedObject } from "../api/queries";
import { ErrorBanner, Loading, Panel, StateBadge, TypeIcon } from "../components/common";
import { locale, t } from "../i18n/i18n";
import { PartPicker } from "./Structure";

// Alternates and substitutes (roadmap 4.8).

type UsedPart = Schemas["UsedPart"];
const qty = (n: number) => n.toLocaleString(locale(), { maximumFractionDigits: 6 });

function PartCells({ part }: { part: UsedPart }) {
  if (!part.readable)
    return (
      <>
        <td className="icon-cell">
          <Lock size={14} className="icon-muted" />
        </td>
        <td colSpan={4} className="muted">
          {t("A part you don't have access to")}
        </td>
      </>
    );
  return (
    <>
      <td className="icon-cell">{part.typeId && <TypeIcon typeId={part.typeId} />}</td>
      <td className="nowrap">
        <Link className="num-link" to={`/objects/${part.id}`}>
          {part.number}
        </Link>
      </td>
      <td>{part.name}</td>
      <td className="version">{part.version}</td>
      <td>{part.state && <StateBadge state={part.state} />}</td>
    </>
  );
}

/** A part's Replacements tab: its alternates (anywhere), and the lines it substitutes on. */
export function ReplacementsTab({ obj }: { obj: VersionedObject }) {
  const qc = useQueryClient();
  const { data: permissions } = usePermissions(obj.contextId, obj.typeId);
  const canEdit = !!permissions?.has("MODIFY");
  const [twoWay, setTwoWay] = useState(true);
  const alternates = useQuery({
    queryKey: ["alternates", obj.id],
    queryFn: () => unwrap(api.GET("/api/v1/objects/{id}/alternates", { params: { path: { id: obj.id } } })),
  });
  const uses = useQuery({
    queryKey: ["substituteUses", obj.id],
    queryFn: () => unwrap(api.GET("/api/v1/objects/{id}/substitute-uses", { params: { path: { id: obj.id } } })),
  });
  const refresh = () => void qc.invalidateQueries({ queryKey: ["alternates"] });
  const remove = useMutation({
    mutationFn: (alternateId: string) =>
      unwrap(api.DELETE("/api/v1/objects/{id}/alternates/{alternateId}", { params: { path: { id: obj.id, alternateId } } })),
    onSuccess: refresh,
  });
  const [error, setError] = useState<unknown>();
  if (alternates.isLoading) return <Loading />;
  return (
    <div className="details-stack">
      <Panel title={t("Alternates")} note={t("Parts that may replace this one wherever it's used")} flush>
        <ErrorBanner error={alternates.error ?? remove.error ?? error} />
        <table className="grid" aria-label={t("Alternates")}>
          <thead>
            <tr>
              <th className="icon-cell" />
              <th>{t("Number")}</th>
              <th>{t("Name")}</th>
              <th>{t("Version")}</th>
              <th>{t("State")}</th>
              <th>{t("Both ways")}</th>
              <th>{t("Notes")}</th>
              {canEdit && <th />}
            </tr>
          </thead>
          <tbody>
            {alternates.data?.map((a) => (
              <tr key={a.part.id}>
                <PartCells part={a.part} />
                <td>{a.twoWay ? t("Yes") : t("No")}</td>
                <td className="muted">{a.notes}</td>
                {canEdit && (
                  <td style={{ width: 1 }}>
                    <button
                      className="icon-btn"
                      aria-label={t("Remove the alternate {number}", { number: a.part.number ?? "" })}
                      onClick={() => remove.mutate(a.part.id)}
                    >
                      <Trash2 size={13} />
                    </button>
                  </td>
                )}
              </tr>
            ))}
            {alternates.data?.length === 0 && (
              <tr>
                <td colSpan={canEdit ? 8 : 7} className="empty">
                  {t("No alternates.")}
                </td>
              </tr>
            )}
          </tbody>
        </table>
        {canEdit && (
          <div className="add-line">
            <PartPicker
              exclude={obj.id}
              contextId={obj.contextId}
              label={t("Alternate to add")}
              onPick={async (p) => {
                try {
                  await unwrap(
                    api.POST("/api/v1/objects/{id}/alternates", { params: { path: { id: obj.id } }, body: { alternateId: p.id, twoWay } }),
                  );
                  setError(undefined);
                  refresh();
                } catch (e) {
                  setError(e);
                }
              }}
            />
            <label className="row small" style={{ gap: 4 }}>
              <input type="checkbox" checked={twoWay} onChange={(e) => setTwoWay(e.target.checked)} /> {t("Both ways")}
            </label>
          </div>
        )}
      </Panel>
      <Panel title={t("Substitute for")} note={t("Lines in other parts' structures where this part may replace the usual one")} flush>
        <table className="grid" aria-label={t("Substitute for")}>
          <thead>
            <tr>
              <th className="icon-cell" />
              <th>{t("Used in")}</th>
              <th>{t("Name")}</th>
              <th>{t("Version")}</th>
              <th>{t("State")}</th>
              <th className="num">{t("Line")}</th>
              <th>{t("Instead of")}</th>
              <th className="num">{t("Quantity")}</th>
            </tr>
          </thead>
          <tbody>
            {uses.data?.map((u) => (
              <tr key={`${u.parent.id}:${u.lineNumber}`}>
                <PartCells part={u.parent} />
                <td className="num">{u.lineNumber}</td>
                <td>
                  {u.original.readable ? (
                    <Link to={`/objects/${u.original.id}`}>
                      {u.original.number} {u.original.name}
                    </Link>
                  ) : (
                    t("(no access)")
                  )}
                </td>
                <td className="num">{qty(u.quantity)}</td>
              </tr>
            ))}
            {uses.data?.length === 0 && (
              <tr>
                <td colSpan={8} className="empty">
                  {t("Not a substitute on any line.")}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </Panel>
    </div>
  );
}

/** The substitutes of one working-copy line, editable (roadmap 4.8). */
export function SubstitutesEditor({ obj, usageId, onChanged }: { obj: VersionedObject; usageId: string; onChanged: () => void }) {
  const qc = useQueryClient();
  const { data } = useQuery({
    queryKey: ["substitutes", obj.id, usageId],
    queryFn: () => unwrap(api.GET("/api/v1/objects/{id}/usages/{usageId}/substitutes", { params: { path: { id: obj.id, usageId } } })),
  });
  const [error, setError] = useState<unknown>();
  const refresh = () => {
    void qc.invalidateQueries({ queryKey: ["substitutes", obj.id, usageId] });
    onChanged();
  };
  const remove = useMutation({
    mutationFn: (substituteId: string) =>
      unwrap(
        api.DELETE("/api/v1/objects/{id}/usages/{usageId}/substitutes/{substituteId}", {
          params: { path: { id: obj.id, usageId, substituteId } },
        }),
      ),
    onSuccess: refresh,
  });
  return (
    <div className="substitutes">
      <strong className="small">{t("Substitutes on this line")}</strong>
      <ErrorBanner error={error ?? remove.error} />
      <ul>
        {data?.map((s) => (
          <li key={s.part.id}>
            {s.part.typeId && <TypeIcon typeId={s.part.typeId} />} <strong>{s.part.number}</strong> {s.part.name}
            {s.notes && <span className="muted"> · {s.notes}</span>}
            <button
              className="icon-btn"
              aria-label={t("Remove the substitute {number}", { number: s.part.number ?? "" })}
              onClick={() => remove.mutate(s.part.id)}
            >
              <Trash2 size={12} />
            </button>
          </li>
        ))}
        {data?.length === 0 && <li className="muted">{t("None yet.")}</li>}
      </ul>
      <PartPicker
        exclude={obj.id}
        contextId={obj.contextId}
        label={t("Substitute to add")}
        onPick={async (p) => {
          try {
            await unwrap(
              api.POST("/api/v1/objects/{id}/usages/{usageId}/substitutes", {
                params: { path: { id: obj.id, usageId } },
                body: { substituteId: p.id },
              }),
            );
            setError(undefined);
            refresh();
          } catch (e) {
            setError(e);
          }
        }}
      />
    </div>
  );
}
