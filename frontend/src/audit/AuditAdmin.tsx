import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Lock, Scissors } from "lucide-react";
import { useState } from "react";
import { api, unwrap } from "../api/client";
import { ErrorBanner, Loading, Panel, PrincipalName, When } from "../components/common";
import { t } from "../i18n/i18n";

/** Administration → Audit (roadmap 9.9): which kinds of events are recorded, and how long they are kept. */
export function AuditAdmin() {
  const qc = useQueryClient();
  const { data: config, isLoading } = useQuery({ queryKey: ["auditConfig"], queryFn: () => unwrap(api.GET("/api/v1/audit/config")) });
  const { data: kinds } = useQuery({ queryKey: ["auditKinds"], queryFn: () => unwrap(api.GET("/api/v1/audit/kinds")) });
  const [retention, setRetention] = useState<string | null>(null);
  const refresh = () => {
    void qc.invalidateQueries({ queryKey: ["auditConfig"] });
    void qc.invalidateQueries({ queryKey: ["auditKinds"] });
  };
  const save = useMutation({
    mutationFn: (body: { excluded: string[]; retentionDays: number | null }) => unwrap(api.PUT("/api/v1/audit/config", { body })),
    onSuccess: refresh,
  });
  const purge = useMutation({ mutationFn: () => unwrap(api.POST("/api/v1/audit/purge")), onSuccess: refresh });
  if (isLoading || !config) return <Loading />;
  const excluded = new Set(config.excluded);
  const toggle = (kind: string) => {
    const next = new Set(excluded);
    if (next.has(kind)) next.delete(kind);
    else next.add(kind);
    save.mutate({ excluded: [...next], retentionDays: config.retentionDays ?? null });
  };
  const days = retention ?? (config.retentionDays ? String(Math.round(config.retentionDays / 365)) : "");
  return (
    <div className="stack">
      <ErrorBanner error={save.error ?? purge.error} />
      <Panel
        title={t("What is audited")}
        note={
          config.updatedAt ? (
            <>
              {t("Changed")} <When iso={config.updatedAt} /> · <PrincipalName id={config.updatedBy!} />
            </>
          ) : (
            t("Everything, until you leave some kinds out")
          )
        }
        flush
      >
        <table className="grid" aria-label={t("Event kinds")}>
          <thead>
            <tr>
              <th>{t("Kind of event")}</th>
              <th className="num">{t("Entries")}</th>
              <th>{t("Audited")}</th>
            </tr>
          </thead>
          <tbody>
            {kinds?.map((k) => (
              <tr key={k.kind}>
                <td>
                  <code>{k.kind}</code>
                </td>
                <td className="num">{k.entries.toLocaleString()}</td>
                <td>
                  {k.alwaysAudited ? (
                    <span className="tag">
                      <Lock size={11} aria-hidden /> {t("always")}
                    </span>
                  ) : (
                    <label className="check">
                      <input type="checkbox" checked={!k.excluded} disabled={save.isPending} onChange={() => toggle(k.kind)} />
                      {k.excluded ? t("left out") : t("recorded")}
                    </label>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Panel>
      <Panel title={t("How long entries are kept")} note={t("Older entries are purged every night; each purge is itself on record")}>
        <div className="row audit-retention">
          <label className="check">
            <input
              type="checkbox"
              checked={config.retentionDays != null}
              onChange={(e) => save.mutate({ excluded: config.excluded, retentionDays: e.target.checked ? 3650 : null })}
            />
            {t("Purge entries older than")}
          </label>
          <input
            type="number"
            min={1}
            aria-label={t("Years")}
            disabled={config.retentionDays == null}
            value={days}
            onChange={(e) => setRetention(e.target.value)}
            onBlur={() =>
              retention && save.mutate({ excluded: config.excluded, retentionDays: Math.max(30, Math.round(Number(retention) * 365)) })
            }
          />
          <span>{t("years")}</span>
          <button className="btn small" disabled={config.retentionDays == null || purge.isPending} onClick={() => purge.mutate()}>
            <Scissors size={13} aria-hidden /> {t("Purge now")}
          </button>
          {purge.data && <span className="muted small">{t("{n} entries purged", { n: purge.data.purged ?? 0 })}</span>}
        </div>
        {config.retentionDays == null && <p className="muted small">{t("Kept for ever.")}</p>}
      </Panel>
    </div>
  );
}
