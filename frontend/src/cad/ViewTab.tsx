import { lazy, Suspense } from "react";
import { Link } from "react-router";
import type { VersionedObject } from "../api/queries";
import { Loading, Panel } from "../components/common";
import { t } from "../i18n/i18n";
import { config } from "../config";
import { useViewable } from "./Viewable";

const Viewer3D = lazy(() => import("./Viewer3D").then((m) => ({ default: m.Viewer3D })));
const InspectTools = lazy(() => import("./Inspect").then((m) => ({ default: m.InspectTools })));

/** The 3D tab (roadmap 6.8): the model's viewable in the browser. [modelId]: the model to show. */
export function ViewTab({ obj, modelId, mine }: { obj: VersionedObject; modelId: string; mine: boolean }) {
  const { data } = useViewable(modelId);
  if (!data) return <Loading />;
  if (data.status !== "READY" || !data.url) {
    return (
      <Panel title={t("3D view")}>
        <p className="muted">
          {data.status === "QUEUED" || data.status === "RUNNING"
            ? t("The model is being converted; it shows here when it's ready.")
            : t("There's nothing to show yet: the model has no converted STEP file.")}{" "}
          <Link to={`/objects/${modelId}?tab=cad`}>{t("Model")}</Link>
        </p>
      </Panel>
    );
  }
  return (
    <Suspense fallback={<Loading />}>
      <Viewer3D
        url={data.url.startsWith("/") ? config.apiBase + data.url : data.url}
        title={`${obj.number} · ${data.version}`}
        tools={(h) => <InspectTools h={h} objectId={modelId} editable={mine && modelId === obj.id} />}
      />
    </Suspense>
  );
}
