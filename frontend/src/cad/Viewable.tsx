import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckCircle2, CircleAlert, Download, LoaderCircle, RefreshCw } from "lucide-react";
import { Link } from "react-router";
import { api, unwrap } from "../api/client";
import { ErrorBanner, Panel, formatDate } from "../components/common";
import { t } from "../i18n/i18n";

export const useViewable = (objectId: string) =>
  useQuery({
    queryKey: ["viewable", objectId],
    queryFn: () => unwrap(api.GET("/api/v1/objects/{id}/cad/viewable", { params: { path: { id: objectId } } })),
    refetchInterval: (q) => (q.state.data?.status === "QUEUED" || q.state.data?.status === "RUNNING" ? 2000 : false),
  });

/** Conversion status (roadmap 6.6): whether the model can be viewed in the browser. */
export function ViewablePanel({ objectId }: { objectId: string }) {
  const qc = useQueryClient();
  const { data } = useViewable(objectId);
  const convert = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/objects/{id}/cad/viewable/convert", { params: { path: { id: objectId } } })),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["viewable", objectId] }),
  });
  if (!data || (data.status === "NONE" && !data.fileName)) return null;
  const busy = data.status === "QUEUED" || data.status === "RUNNING";
  return (
    <Panel
      title={t("Viewable")}
      note={
        data.modelId !== objectId ? (
          <>
            {t("From its generic,")} <Link to={`/objects/${data.modelId}?tab=cad`}>{t("which holds the file")}</Link>
          </>
        ) : (
          t("The model converted for viewing in the browser (glTF), made when it's checked in.")
        )
      }
      actions={
        <>
          {data.url && (
            <a className="btn small ghost" href={data.url} download>
              <Download size={13} aria-hidden /> .glb
            </a>
          )}
          {!busy && (
            <button className="btn small ghost" disabled={convert.isPending} onClick={() => convert.mutate()}>
              <RefreshCw size={13} aria-hidden /> {data.status === "NONE" ? t("Convert") : t("Convert again")}
            </button>
          )}
        </>
      }
    >
      <div className={`viewable-status ${data.status.toLowerCase()}`}>
        {busy && <LoaderCircle size={16} className="spin" aria-hidden />}
        {data.status === "READY" && <CheckCircle2 size={16} aria-hidden />}
        {data.status === "FAILED" && <CircleAlert size={16} aria-hidden />}
        <strong>
          {data.status === "QUEUED"
            ? t("Waiting to be converted")
            : data.status === "RUNNING"
              ? t("Converting…")
              : data.status === "READY"
                ? t("Ready to view")
                : data.status === "NONE"
                  ? t("Not converted yet")
                  : t("The conversion failed")}
        </strong>
        <span className="muted">
          {data.fileName} · {data.version}
          {data.meshes != null &&
            ` · ${t("{meshes} bodies, {triangles} triangles", { meshes: data.meshes, triangles: data.triangles ?? 0 })}`}
          {data.updatedAt && ` · ${formatDate(data.updatedAt)}`}
        </span>
        {data.jobId && (
          <Link className="small" to="/admin?tab=jobs">
            {t("Job")}
          </Link>
        )}
      </div>
      {data.error && <div className="banner error">{data.error}</div>}
      <ErrorBanner error={convert.error} />
    </Panel>
  );
}
