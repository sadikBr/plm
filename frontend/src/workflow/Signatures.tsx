import { useQuery } from "@tanstack/react-query";
import { PenLine, ShieldAlert, ShieldCheck } from "lucide-react";
import { api, unwrap } from "../api/client";
import { Panel } from "../components/common";
import { t } from "../i18n/i18n";

/** Electronic signatures on an object (roadmap 5.6): who, what it meant, when, which version. */
export function SignaturesPanel({ objectId }: { objectId: string }) {
  const { data } = useQuery({
    queryKey: ["signatures", objectId],
    queryFn: () => unwrap(api.GET("/api/v1/objects/{id}/signatures", { params: { path: { id: objectId } } })),
  });
  if (!data?.length) return null;
  return (
    <Panel title={t("Electronic signatures")} flush>
      <table className="grid" aria-label={t("Electronic signatures")}>
        <thead>
          <tr>
            <th>{t("Signed by")}</th>
            <th>{t("Meaning")}</th>
            <th>{t("Version")}</th>
            <th>{t("When (UTC)")}</th>
            <th>{t("Record")}</th>
          </tr>
        </thead>
        <tbody>
          {data.map((s) => (
            <tr key={s.id}>
              <td>
                <PenLine size={13} aria-hidden className="icon-muted" /> <strong>{s.displayName}</strong>{" "}
                <span className="muted small">({s.username})</span>
              </td>
              <td>
                {s.meaning}
                {s.comment && <div className="muted small">“{s.comment}”</div>}
              </td>
              <td>{s.objectVersion}</td>
              <td className="nowrap mono small">{s.signedAt.replace("T", " ").slice(0, 19)}</td>
              <td>
                {s.intact ? (
                  <span className="tag allow">
                    <ShieldCheck size={12} aria-hidden /> {t("intact")}
                  </span>
                ) : (
                  <span className="tag deny">
                    <ShieldAlert size={12} aria-hidden /> {t("altered")}
                  </span>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </Panel>
  );
}
