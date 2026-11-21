import { useQuery } from "@tanstack/react-query";
import { lazy, Suspense, useState } from "react";
import { api, unwrap } from "../api/client";
import type { VersionedObject } from "../api/queries";
import { Loading, Panel } from "../components/common";
import { useContent } from "../components/ObjectFiles";
import { t } from "../i18n/i18n";
import { usePublishSettings } from "../publishing/Publishing";

const SheetViewer = lazy(() => import("./SheetViewer").then((m) => ({ default: m.SheetViewer })));

export const isPdf = (c: { contentType: string; fileName: string }) => c.contentType === "application/pdf" || /\.pdf$/i.test(c.fileName);

/** The 2D tab (roadmap 6.11): an object's PDFs in the browser, the primary file first. */
export function SheetTab({ obj }: { obj: VersionedObject }) {
  const { data: items } = useContent(obj.id, obj.latest.id, false);
  const { data: settings } = usePublishSettings();
  const pdfs = (items ?? []).filter(isPdf).sort((a, b) => (a.role === b.role ? 0 : a.role === "PRIMARY" ? -1 : 1));
  const [chosen, setChosen] = useState<string | null>(null);
  if (!items || !settings) return <Loading />;
  const file = pdfs.find((p) => p.id === chosen) ?? pdfs[0];
  if (!file) return <Panel title={t("2D view")}>{t("There's no PDF to show.")}</Panel>;
  return (
    <>
      {pdfs.length > 1 && (
        <div className="row" style={{ marginBottom: 8 }}>
          <label className="field" style={{ minWidth: 280 }}>
            <span>{t("File")}</span>
            <select value={file.id} onChange={(e) => setChosen(e.target.value)}>
              {pdfs.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.fileName}
                </option>
              ))}
            </select>
          </label>
        </div>
      )}
      <Suspense fallback={<Loading />}>
        <SheetViewer key={file.id} objectId={obj.id} contentId={file.id} fileName={file.fileName} watermark={settings.watermarkDownloads} />
      </Suspense>
    </>
  );
}

/** Whether [objectId] (a document: [enabled]) has a PDF to show in the 2D tab. */
export function useHasPdf(objectId: string, enabled: boolean) {
  const { data } = useQuery({
    queryKey: ["hasPdf", objectId],
    queryFn: () => unwrap(api.GET("/api/v1/objects/{id}/content", { params: { path: { id: objectId } } })),
    enabled,
  });
  return !!data?.some(isPdf);
}
