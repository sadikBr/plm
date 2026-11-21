import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { FileDown, FileText, Send } from "lucide-react";
import { api, authorizedFetch, unwrap, ApiError } from "../api/client";
import { ErrorBanner, Panel, PrincipalName, StateBadge, formatDate } from "../components/common";
import { t, tn } from "../i18n/i18n";

export const usePublishSettings = () =>
  useQuery({ queryKey: ["publishSettings"], queryFn: () => unwrap(api.GET("/api/v1/publish-settings")), staleTime: 60_000 });

/** Fetches a PDF the API makes (signed in) and saves or opens it. */
export async function fetchPdf(path: string, fileName: string, open = false): Promise<void> {
  const r = await authorizedFetch(path);
  if (!r.ok) throw new ApiError(r.status, (await r.json().catch(() => undefined)) as never);
  const url = URL.createObjectURL(await r.blob());
  if (open) {
    window.open(url, "_blank", "noopener");
  } else {
    const a = document.createElement("a");
    a.href = url;
    a.download = fileName;
    a.click();
  }
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

/** Publications of an object (roadmap 6.7), its data sheet, and publishing by hand. */
export function PublicationsPanel({ objectId, number }: { objectId: string; number: string }) {
  const qc = useQueryClient();
  const { data } = useQuery({
    queryKey: ["publications", objectId],
    queryFn: () => unwrap(api.GET("/api/v1/objects/{id}/publications", { params: { path: { id: objectId } } })),
    refetchInterval: (q) => (q.state.data?.some((p) => p.status === "QUEUED") ? 2000 : false),
  });
  const publish = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/objects/{id}/publications", { params: { path: { id: objectId } } })),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["publications", objectId] }),
  });
  const sheet = useMutation({ mutationFn: () => fetchPdf(`/api/v1/objects/${objectId}/datasheet.pdf`, `${number}-data-sheet.pdf`) });
  const open = useMutation({
    mutationFn: (p: { id: string; fileName: string }) => fetchPdf(`/api/v1/publications/${p.id}/pdf`, p.fileName),
  });
  return (
    <Panel
      title={t("Publications")}
      note={t("PDFs published for its versions, as the publishing rules say.")}
      flush
      actions={
        <>
          <button className="btn small ghost" disabled={sheet.isPending} onClick={() => sheet.mutate()}>
            <FileText size={13} aria-hidden /> {t("Data sheet (PDF)")}
          </button>
          <button className="btn small ghost" disabled={publish.isPending} onClick={() => publish.mutate()}>
            <Send size={13} aria-hidden /> {t("Publish now")}
          </button>
        </>
      }
    >
      <ErrorBanner error={publish.error ?? sheet.error ?? open.error} />
      {!data?.length ? (
        <div className="empty small">{t("Nothing published yet.")}</div>
      ) : (
        <table className="grid" aria-label={t("Publications")}>
          <thead>
            <tr>
              <th>{t("Version")}</th>
              <th>{t("Made from")}</th>
              <th>{t("By rule")}</th>
              <th>{t("When")}</th>
              <th>{t("Status")}</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {data.map((p) => (
              <tr key={p.id}>
                <td className="nowrap">
                  {p.version} <StateBadge state={p.state} />
                </td>
                <td>{p.source === "FILE" ? t("its PDF, stamped") : p.source === "DATASHEET" ? t("a data sheet") : "—"}</td>
                <td>{p.rule ?? <span className="muted">{t("by hand")}</span>}</td>
                <td className="nowrap">
                  {formatDate(p.createdAt)} · <PrincipalName id={p.createdBy} />
                </td>
                <td>
                  {p.status === "READY" ? (
                    <span className="tag allow">{tn("{n} page", "{n} pages", p.pages ?? 0)}</span>
                  ) : p.status === "FAILED" ? (
                    <span className="tag deny" title={p.error ?? ""}>
                      {t("failed")}
                    </span>
                  ) : (
                    <span className="tag">{t("publishing…")}</span>
                  )}
                </td>
                <td className="row-actions">
                  {p.status === "READY" && (
                    <button
                      className="btn ghost small"
                      onClick={() => open.mutate({ id: p.id, fileName: p.fileName ?? "publication.pdf" })}
                    >
                      <FileDown size={13} aria-hidden /> PDF
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Panel>
  );
}
