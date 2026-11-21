import { Ruler, Scissors, X } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import * as THREE from "three";
import { t } from "../i18n/i18n";
import type { ViewerHandle } from "./Viewer3D";

/** A point picked on the model: where, on which face (its normal), both in viewer coordinates. */
type Pick = { point: THREE.Vector3; normal: THREE.Vector3 };

const pickOf = (hit: THREE.Intersection): Pick => ({
  point: hit.point.clone(),
  normal: (hit.face?.normal.clone() ?? new THREE.Vector3(0, 1, 0)).transformDirection(hit.object.matrixWorld),
});

const mm = (v: number) => `${v.toFixed(2)} mm`;
const deg = (r: number) => `${THREE.MathUtils.radToDeg(r).toFixed(2)}°`;

/** Markers and lines drawn over the model: always on top, never picked. */
function helpers(h: ViewerHandle) {
  const group = new THREE.Group();
  group.userData.helper = true;
  group.renderOrder = 10;
  h.scene.add(group);
  const size = h.radius / 90;
  const sphere = new THREE.SphereGeometry(size, 16, 12);
  const mark = (p: THREE.Vector3, color: string) => {
    const m = new THREE.Mesh(sphere, new THREE.MeshBasicMaterial({ color, depthTest: false }));
    m.position.copy(p);
    m.renderOrder = 10;
    m.userData.helper = true;
    group.add(m);
  };
  const line = (a: THREE.Vector3, b: THREE.Vector3, color: string) => {
    const l = new THREE.Line(new THREE.BufferGeometry().setFromPoints([a, b]), new THREE.LineBasicMaterial({ color, depthTest: false }));
    l.renderOrder = 10;
    l.userData.helper = true;
    group.add(l);
  };
  return {
    mark,
    line,
    clear: () => group.clear(),
    dispose: () => void h.scene.remove(group),
  };
}

type Mode = "point" | "distance" | "angle";

/**
 * Measuring (roadmap 6.9): a point's coordinates, the distance between two points (and between two
 * parallel faces), and the angle between two faces, in the model's own coordinates (millimetres).
 */
function Measure({ h, onClose }: { h: ViewerHandle; onClose: () => void }) {
  const [mode, setMode] = useState<Mode>("distance");
  const [picks, setPicks] = useState<Pick[]>([]);
  const draw = useMemo(() => helpers(h), [h]);
  const tag = useRef<HTMLDivElement>(null);
  const needed = mode === "point" ? 1 : 2;

  useEffect(() => {
    h.setPicker((hit) => setPicks((p) => (p.length >= needed ? [pickOf(hit)] : [...p, pickOf(hit)])));
    return () => h.setPicker(null);
  }, [h, needed]);
  useEffect(() => () => draw.dispose(), [draw]);
  useEffect(() => setPicks([]), [mode]);

  // Markers, the line between them, and a label that follows the camera.
  useEffect(() => {
    draw.clear();
    picks.forEach((p) => draw.mark(p.point, "#e8590c"));
    const [a, b] = picks;
    if (a && b) draw.line(a.point, b.point, "#e8590c");
    const anchor = a && b ? a.point.clone().add(b.point).multiplyScalar(0.5) : a?.point;
    const place = () => {
      if (!tag.current) return;
      if (!anchor) return void (tag.current.style.display = "none");
      const s = anchor.clone().project(h.camera);
      const r = h.renderer.domElement;
      tag.current.style.display = "";
      tag.current.style.left = `${((s.x + 1) / 2) * r.clientWidth}px`;
      tag.current.style.top = `${((1 - s.y) / 2) * r.clientHeight}px`;
    };
    place();
    h.controls.addEventListener("change", place);
    h.render();
    return () => h.controls.removeEventListener("change", place);
  }, [picks, draw, h]);

  // Results, in the model's coordinates.
  const inModel = picks.map((p) => p.point.clone().applyMatrix4(h.toModel));
  const normals = picks.map((p) => p.normal.clone().transformDirection(h.toModel));
  const [a, b] = inModel;
  const [na, nb] = normals;
  let rows: [string, string][] = [];
  let headline = "";
  if (mode === "point" && a) {
    rows = [
      ["X", mm(a.x)],
      ["Y", mm(a.y)],
      ["Z", mm(a.z)],
    ];
    headline = `${a.x.toFixed(1)}, ${a.y.toFixed(1)}, ${a.z.toFixed(1)}`;
  }
  if (mode === "distance" && a && b) {
    const d = b.clone().sub(a);
    headline = mm(d.length());
    rows = [
      [t("Distance"), mm(d.length())],
      ["ΔX", mm(Math.abs(d.x))],
      ["ΔY", mm(Math.abs(d.y))],
      ["ΔZ", mm(Math.abs(d.z))],
    ];
    if (na && nb && Math.abs(na.dot(nb)) > 0.9999) rows.push([t("Between the faces"), mm(Math.abs(d.dot(na)))]);
  }
  if (mode === "angle" && na && nb) {
    const angle = na.angleTo(nb);
    headline = deg(angle);
    rows = [
      [t("Between the faces"), deg(angle)],
      [t("Supplement"), deg(Math.PI - angle)],
    ];
  }
  const hint =
    mode === "point"
      ? t("Click a point on the model.")
      : mode === "distance"
        ? picks.length === 1
          ? t("Click the second point.")
          : t("Click two points.")
        : picks.length === 1
          ? t("Click the second face.")
          : t("Click two faces.");

  return createPortal(
    <>
      <div className="tag" ref={tag}>
        {headline}
      </div>
      <section className="viewer-panel" aria-label={t("Measure")}>
        <div className="row spread">
          <h3>{t("Measure")}</h3>
          <button className="icon-btn" aria-label={t("Close")} onClick={onClose}>
            <X size={14} />
          </button>
        </div>
        <div className="seg" role="radiogroup">
          {(["point", "distance", "angle"] as Mode[]).map((m) => (
            <button
              key={m}
              role="radio"
              aria-checked={mode === m}
              className={`btn small ghost ${mode === m ? "on" : ""}`}
              onClick={() => setMode(m)}
            >
              {m === "point" ? t("Point") : m === "distance" ? t("Distance") : t("Angle")}
            </button>
          ))}
        </div>
        {rows.length ? (
          <dl>
            {rows.map(([k, v]) => (
              <div key={k} style={{ display: "contents" }}>
                <dt>{k}</dt>
                <dd>{v}</dd>
              </div>
            ))}
          </dl>
        ) : (
          <p className="muted small">{hint}</p>
        )}
        {rows.length > 0 && <p className="muted small">{t("Model coordinates. Click again to start over.")}</p>}
      </section>
    </>,
    h.overlay,
  );
}

type Axis = "X" | "Y" | "Z" | "face";

/**
 * Sections (roadmap 6.9): a plane along a model axis or a picked face, moved through the model,
 * the cut bodies capped (stencil caps, so cut solids look solid).
 */
function Section({ h, onClose }: { h: ViewerHandle; onClose: () => void }) {
  const [axis, setAxis] = useState<Axis>("X");
  const [face, setFace] = useState<Pick | null>(null);
  const [flip, setFlip] = useState(false);
  const [cap, setCap] = useState(true);
  const [at, setAt] = useState(0.5);
  const plane = useMemo(() => new THREE.Plane(), []);

  // The plane's normal (viewer coordinates) and the range it sweeps over the model.
  const normal = useMemo(() => {
    if (axis === "face") return face ? face.normal.clone().negate() : null;
    const v = new THREE.Vector3(axis === "X" ? 1 : 0, axis === "Y" ? 1 : 0, axis === "Z" ? 1 : 0);
    return v.transformDirection(h.fromModel);
  }, [axis, face, h]);
  const range = useMemo(() => {
    if (!normal) return [0, 0] as const;
    const box = new THREE.Box3().setFromObject(h.model);
    const ds = [0, 1].flatMap((x) =>
      [0, 1].flatMap((y) =>
        [0, 1].map((z) => normal.dot(new THREE.Vector3(x ? box.max.x : box.min.x, y ? box.max.y : box.min.y, z ? box.max.z : box.min.z))),
      ),
    );
    return [Math.min(...ds), Math.max(...ds)] as const;
  }, [normal, h]);
  useEffect(() => {
    if (axis === "face" && face) setAt((face.point.dot(normal!) - range[0]) / (range[1] - range[0] || 1));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [face]);

  // Picking a face, when sectioning along one.
  useEffect(() => {
    if (axis !== "face") return;
    h.setPicker((hit) => setFace(pickOf(hit)));
    return () => h.setPicker(null);
  }, [h, axis]);

  // The clipping on every body, the stencil helpers and the cap.
  const parts = useMemo(() => {
    const bodies: THREE.Mesh[] = [];
    h.model.traverse((o) => o instanceof THREE.Mesh && !o.userData.helper && bodies.push(o));
    const stencils: THREE.Mesh[] = [];
    // Counts how often each pixel's ray crosses a body's surface behind the plane: odd means inside.
    // Parity (invert), not front/back counting, since tessellated faces aren't consistently wound.
    const material = new THREE.MeshBasicMaterial({
      side: THREE.DoubleSide,
      clippingPlanes: [plane],
      colorWrite: false,
      depthWrite: false,
      depthTest: false,
      stencilWrite: true,
      stencilFunc: THREE.AlwaysStencilFunc,
      stencilFail: THREE.InvertStencilOp,
      stencilZFail: THREE.InvertStencilOp,
      stencilZPass: THREE.InvertStencilOp,
    });
    bodies.forEach((body) => {
      const s = new THREE.Mesh(body.geometry, material);
      s.userData.helper = true;
      s.renderOrder = 1;
      s.raycast = () => {};
      body.add(s);
      stencils.push(s);
    });
    const capMesh = new THREE.Mesh(
      new THREE.PlaneGeometry(h.radius * 6, h.radius * 6),
      new THREE.MeshStandardMaterial({
        color: "#8f98a6",
        metalness: 0.1,
        roughness: 0.75,
        side: THREE.DoubleSide,
        stencilWrite: true,
        stencilRef: 0,
        stencilFunc: THREE.NotEqualStencilFunc,
        stencilFail: THREE.ReplaceStencilOp,
        stencilZFail: THREE.ReplaceStencilOp,
        stencilZPass: THREE.ReplaceStencilOp,
        polygonOffset: true,
        polygonOffsetFactor: -1,
        polygonOffsetUnits: -1,
      }),
    );
    capMesh.userData.helper = true;
    capMesh.renderOrder = 1.1;
    capMesh.raycast = () => {};
    capMesh.onAfterRender = (r) => r.clearStencil();
    h.scene.add(capMesh);
    return { bodies, stencils, capMesh };
  }, [h, plane]);
  useEffect(
    () => () => {
      parts.bodies.forEach((b) => ((b.material as THREE.Material).clippingPlanes = null));
      parts.stencils.forEach((s) => s.parent?.remove(s));
      h.scene.remove(parts.capMesh);
      h.render();
    },
    [parts, h],
  );

  useEffect(() => {
    const on = !!normal;
    if (normal) {
      const n = flip ? normal.clone().negate() : normal;
      const d = range[0] + (range[1] - range[0]) * at;
      plane.set(n.clone().negate(), flip ? -d : d);
      // Keeps n·p ≤ d (flipped: n·p ≥ d); the cap lies on the plane.
      parts.capMesh.position.copy(plane.coplanarPoint(new THREE.Vector3()));
      parts.capMesh.lookAt(parts.capMesh.position.clone().add(plane.normal));
    }
    parts.bodies.forEach((b) => {
      const m = b.material as THREE.Material;
      m.clippingPlanes = on ? [plane] : null;
      m.needsUpdate = true;
    });
    parts.stencils.forEach((s) => (s.visible = on && cap));
    parts.capMesh.visible = on && cap;
    h.render();
  }, [normal, range, at, flip, cap, plane, parts, h]);

  // Where the plane is, in the model's coordinates: along its axis, or from the picked face.
  const position = () => {
    if (!normal) return "";
    const d = range[0] + (range[1] - range[0]) * at;
    if (axis === "face") return face ? `${mm((d - face.point.dot(normal)) / h.fromModel.getMaxScaleOnAxis())} ${t("from the face")}` : "";
    const p = normal.clone().multiplyScalar(d).applyMatrix4(h.toModel);
    return `${axis} = ${mm(axis === "X" ? p.x : axis === "Y" ? p.y : p.z)}`;
  };

  return createPortal(
    <section className="viewer-panel" aria-label={t("Section")}>
      <div className="row spread">
        <h3>{t("Section")}</h3>
        <button className="icon-btn" aria-label={t("Close")} onClick={onClose}>
          <X size={14} />
        </button>
      </div>
      <div className="seg" role="radiogroup" aria-label={t("Plane")}>
        {(["X", "Y", "Z", "face"] as Axis[]).map((a) => (
          <button
            key={a}
            role="radio"
            aria-checked={axis === a}
            className={`btn small ghost ${axis === a ? "on" : ""}`}
            onClick={() => setAxis(a)}
          >
            {a === "face" ? t("On a face") : t("Along {axis}", { axis: a })}
          </button>
        ))}
      </div>
      {axis === "face" && !face ? (
        <p className="muted small">{t("Click a flat face: the plane goes through it.")}</p>
      ) : (
        <>
          <label className="field">
            <span>
              {t("Position")} <span className="muted">{position()}</span>
            </span>
            <input
              type="range"
              min={0}
              max={1}
              step={0.001}
              value={at}
              onChange={(e) => setAt(Number(e.target.value))}
              aria-label={t("Position")}
            />
          </label>
          <div className="row" style={{ gap: 12 }}>
            <label className="check">
              <input type="checkbox" checked={flip} onChange={(e) => setFlip(e.target.checked)} /> {t("Flip")}
            </label>
            <label className="check">
              <input type="checkbox" checked={cap} onChange={(e) => setCap(e.target.checked)} /> {t("Cap the cut")}
            </label>
          </div>
        </>
      )}
    </section>,
    h.overlay,
  );
}

/** The viewer's inspection tools: one at a time. */
export function InspectTools({ h }: { h: ViewerHandle }) {
  const [tool, setTool] = useState<"measure" | "section" | null>(null);
  const toggle = (x: "measure" | "section") => setTool(tool === x ? null : x);
  return (
    <>
      <span className="sep" />
      <button
        className={`btn small ghost ${tool === "measure" ? "on" : ""}`}
        aria-pressed={tool === "measure"}
        onClick={() => toggle("measure")}
      >
        <Ruler size={13} aria-hidden /> {t("Measure")}
      </button>
      <button
        className={`btn small ghost ${tool === "section" ? "on" : ""}`}
        aria-pressed={tool === "section"}
        onClick={() => toggle("section")}
      >
        <Scissors size={13} aria-hidden /> {t("Section")}
      </button>
      {tool === "measure" && <Measure h={h} onClose={() => setTool(null)} />}
      {tool === "section" && <Section h={h} onClose={() => setTool(null)} />}
    </>
  );
}
