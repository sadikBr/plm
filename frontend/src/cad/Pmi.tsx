import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Camera, FileUp, Plus, Trash2, X } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import * as THREE from "three";
import { api, unwrap } from "../api/client";
import type { components } from "../api/schema";
import { ErrorBanner, formatDate } from "../components/common";
import { t } from "../i18n/i18n";
import type { ViewerHandle } from "./Viewer3D";

type PmiView = components["schemas"]["PmiView"];
type PmiAnnotation = components["schemas"]["PmiAnnotation"];
type Kind = PmiAnnotation["kind"];

const KINDS: [Kind, string][] = [
  ["DIMENSION", "Dimension"],
  ["TOLERANCE", "Geometric tolerance"],
  ["DATUM", "Datum"],
  ["SURFACE_FINISH", "Surface finish"],
  ["NOTE", "Note"],
];
const kindLabel = (k: Kind) => t(KINDS.find(([x]) => x === k)?.[1] ?? k);

const newId = () => Math.random().toString(36).slice(2, 10);

/** Annotations over the model: each label with its leader to the point it's about. */
function Annotations({ h, items }: { h: ViewerHandle; items: PmiAnnotation[] }) {
  const svg = useRef<SVGSVGElement>(null);
  const labels = useRef<(HTMLDivElement | null)[]>([]);
  useEffect(() => {
    const toScreen = (p: number[]) => {
      const s = new THREE.Vector3().fromArray(p).applyMatrix4(h.fromModel).project(h.camera);
      const r = h.renderer.domElement;
      return { x: ((s.x + 1) / 2) * r.clientWidth, y: ((1 - s.y) / 2) * r.clientHeight, behind: s.z > 1 };
    };
    // A label without a place of its own sits up and right of its point, the same on screen at any zoom.
    const place = () => {
      h.camera.updateMatrixWorld();
      const lines = svg.current?.querySelectorAll("line") ?? [];
      items.forEach((a, i) => {
        const at = toScreen(a.at);
        const label = a.label ? toScreen(a.label) : { x: at.x + 40, y: at.y - 36, behind: at.behind };
        const el = labels.current[i];
        const line = lines[i];
        if (el) {
          el.style.left = `${label.x}px`;
          el.style.top = `${label.y}px`;
          el.style.display = at.behind ? "none" : "";
        }
        if (line) {
          line.setAttribute("x1", `${at.x}`);
          line.setAttribute("y1", `${at.y}`);
          line.setAttribute("x2", `${label.x}`);
          line.setAttribute("y2", `${label.y}`);
          line.style.display = at.behind ? "none" : "";
        }
      });
    };
    place();
    const frame = requestAnimationFrame(place);
    h.controls.addEventListener("change", place);
    return () => {
      cancelAnimationFrame(frame);
      h.controls.removeEventListener("change", place);
    };
  }, [h, items]);
  return createPortal(
    <>
      <svg ref={svg} className="pmi-leaders">
        {items.map((a) => (
          <line key={a.id} />
        ))}
      </svg>
      {items.map((a, i) => (
        <div key={a.id} ref={(el) => void (labels.current[i] = el)} className={`pmi pmi-${a.kind.toLowerCase()}`} title={kindLabel(a.kind)}>
          {a.text}
        </div>
      ))}
    </>,
    h.overlay,
  );
}

/**
 * Model-based definition (roadmap 6.12): the model's PMI views and annotations in the 3D view, and
 * authoring them on the working copy ([editable]): by hand, or from a sidecar `.pmi.json` file.
 */
export function PmiPanel({
  h,
  objectId,
  editable,
  onClose,
}: {
  h: ViewerHandle;
  objectId: string;
  editable: boolean;
  onClose: () => void;
}) {
  const qc = useQueryClient();
  const key = ["pmi", objectId, editable];
  const { data, error } = useQuery({
    queryKey: key,
    queryFn: () =>
      unwrap(api.GET("/api/v1/objects/{id}/cad/pmi", { params: { path: { id: objectId }, query: { workingCopy: editable } } })),
  });
  const [viewId, setViewId] = useState<string | null>(null);
  const [adding, setAdding] = useState<{ kind: Kind; text: string } | null>(null);
  const [picking, setPicking] = useState(false);
  const file = useRef<HTMLInputElement>(null);
  const views = data?.views ?? [];
  const view = views.find((v) => v.id === viewId) ?? null;
  const shown = useMemo(() => (view ? (view.annotations ?? []) : views.flatMap((v) => v.annotations ?? [])), [view, views]);

  const put = useMutation({
    mutationFn: (body: { views: PmiView[]; source?: string }) =>
      unwrap(api.PUT("/api/v1/objects/{id}/cad/pmi", { params: { path: { id: objectId } }, body })),
    onSuccess: (pmi) => qc.setQueryData(key, pmi),
  });
  const change = (fn: (vs: PmiView[]) => PmiView[]) => put.mutate({ views: fn(views) });

  const look = (v: PmiView) => {
    setViewId(v.id);
    if (!v.camera) return;
    const toViewer = (p: number[]) => new THREE.Vector3().fromArray(p).applyMatrix4(h.fromModel).toArray();
    h.restore({ ...h.snapshot(), camera: { position: toViewer(v.camera.position), target: toViewer(v.camera.target) } });
  };
  const cameraNow = () => {
    const s = h.snapshot();
    const toModel = (p: number[]) => new THREE.Vector3().fromArray(p).applyMatrix4(h.toModel).toArray();
    return { position: toModel(s.camera.position), target: toModel(s.camera.target) };
  };

  // Adding an annotation: its text first, then its point on the model.
  useEffect(() => {
    if (!picking || !adding) return;
    h.setPicker((hit) => {
      const at = hit.point.clone().applyMatrix4(h.toModel).toArray();
      const target = view ?? views[0];
      const annotation: PmiAnnotation = { id: newId(), kind: adding.kind, text: adding.text.trim(), at };
      change((vs) =>
        target
          ? vs.map((v) => (v.id === target.id ? { ...v, annotations: [...(v.annotations ?? []), annotation] } : v))
          : [{ id: newId(), name: t("Annotations"), camera: cameraNow(), annotations: [annotation] }],
      );
      setPicking(false);
      setAdding(null);
    });
    return () => h.setPicker(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [h, picking, adding, view, views]);

  const importFile = async (f: File) => {
    try {
      const parsed = JSON.parse(await f.text()) as { views?: PmiView[] };
      put.mutate({ views: parsed.views ?? [], source: f.name });
    } catch {
      put.reset();
      alert(t("{file} isn't a PMI file (JSON with views).", { file: f.name }));
    }
  };

  return (
    <>
      <Annotations h={h} items={shown} />
      {createPortal(
        <section className="viewer-panel" aria-label={t("PMI")}>
          <div className="row spread">
            <h3>{t("PMI")}</h3>
            <button className="icon-btn" aria-label={t("Close")} onClick={onClose}>
              <X size={14} />
            </button>
          </div>
          {error && <ErrorBanner error={error} />}
          {put.error && <ErrorBanner error={put.error} />}
          {data && (
            <p className="muted small">
              {data.source
                ? t("From {file}, on {version}.", { file: data.source, version: data.version })
                : data.updatedAt
                  ? t("Authored on {version}, {date}.", { version: data.version, date: formatDate(data.updatedAt) })
                  : t("{version} has no PMI.", { version: data.version })}
            </p>
          )}
          {views.length > 0 && (
            <ul className="markup-list">
              <li className={!view ? "selected" : ""}>
                <button className="link-btn markup-title" onClick={() => setViewId(null)}>
                  {t("All annotations")}
                </button>
              </li>
              {views.map((v) => (
                <li key={v.id} className={view?.id === v.id ? "selected" : ""}>
                  <span className="row spread">
                    <button className="link-btn markup-title" onClick={() => look(v)}>
                      {v.name}
                    </button>
                    <span className="muted small">{v.annotations?.length ?? 0}</span>
                  </span>
                  {view?.id === v.id && (
                    <ul className="pmi-items">
                      {(v.annotations ?? []).map((a) => (
                        <li key={a.id}>
                          <span className={`pmi-chip pmi-${a.kind.toLowerCase()}`}>{a.text}</span>
                          <span className="muted small">{kindLabel(a.kind)}</span>
                          {editable && (
                            <button
                              className="icon-btn"
                              aria-label={t("Remove {text}", { text: a.text })}
                              onClick={() =>
                                change((vs) =>
                                  vs.map((x) =>
                                    x.id === v.id ? { ...x, annotations: (x.annotations ?? []).filter((y) => y.id !== a.id) } : x,
                                  ),
                                )
                              }
                            >
                              <Trash2 size={12} />
                            </button>
                          )}
                        </li>
                      ))}
                    </ul>
                  )}
                </li>
              ))}
            </ul>
          )}
          {editable ? (
            adding ? (
              <div className="markup-draft">
                <label className="field">
                  <span>{t("Kind")}</span>
                  <select value={adding.kind} onChange={(e) => setAdding({ ...adding, kind: e.target.value as Kind })}>
                    {KINDS.map(([k]) => (
                      <option key={k} value={k}>
                        {kindLabel(k)}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="field">
                  <span>{t("Text")}</span>
                  <input
                    autoFocus
                    value={adding.text}
                    placeholder="Ø22 H7"
                    onChange={(e) => setAdding({ ...adding, text: e.target.value })}
                  />
                </label>
                <div className="row">
                  <button
                    className={`btn small ${picking ? "on" : "primary"}`}
                    disabled={!adding.text.trim()}
                    onClick={() => setPicking(true)}
                  >
                    {picking ? t("Click the model…") : t("Place on the model")}
                  </button>
                  <button
                    className="btn small ghost"
                    onClick={() => {
                      setAdding(null);
                      setPicking(false);
                    }}
                  >
                    {t("Cancel")}
                  </button>
                </div>
                <p className="muted small">{view ? t("Goes in the view {name}.", { name: view.name }) : t("Goes in the first view.")}</p>
              </div>
            ) : (
              <div className="row wrap-tight">
                <button className="btn small" onClick={() => setAdding({ kind: "DIMENSION", text: "" })}>
                  <Plus size={13} aria-hidden /> {t("Annotation")}
                </button>
                <button
                  className="btn small ghost"
                  onClick={() => {
                    const name = prompt(t("The new view's name"));
                    if (name?.trim()) {
                      const v = { id: newId(), name: name.trim(), camera: cameraNow(), annotations: [] };
                      change((vs) => [...vs, v]);
                      setViewId(v.id);
                    }
                  }}
                >
                  <Plus size={13} aria-hidden /> {t("View")}
                </button>
                {view && (
                  <button
                    className="btn small ghost"
                    onClick={() => change((vs) => vs.map((x) => (x.id === view.id ? { ...x, camera: cameraNow() } : x)))}
                  >
                    <Camera size={13} aria-hidden /> {t("Keep this camera")}
                  </button>
                )}
                <button className="btn small ghost" onClick={() => file.current?.click()}>
                  <FileUp size={13} aria-hidden /> {t("Import…")}
                </button>
                <input
                  ref={file}
                  type="file"
                  accept=".json,application/json"
                  hidden
                  onChange={(e) => {
                    const f = e.target.files?.[0];
                    e.target.value = "";
                    if (f) void importFile(f);
                  }}
                />
              </div>
            )
          ) : (
            <p className="muted small">
              {t("Check the model out to add annotations, or attach a .pmi.json file: it's read when the model is checked in.")}
            </p>
          )}
        </section>,
        h.overlay,
      )}
    </>
  );
}
