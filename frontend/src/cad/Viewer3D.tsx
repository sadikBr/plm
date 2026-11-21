import { Box, Eye, EyeOff, Focus, Layers, Maximize2, ScanEye } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import * as THREE from "three";
import { GLTFLoader } from "three/examples/jsm/loaders/GLTFLoader.js";
import { OrbitControls } from "three/examples/jsm/controls/OrbitControls.js";
import { t } from "../i18n/i18n";

/** A line of the model's tree: a named group (assembly, part) of the glTF. */
type TreeItem = { id: string; name: string; depth: number; object: THREE.Object3D; meshes: THREE.Mesh[] };

type View = "iso" | "front" | "top" | "right";

const SELECTED = new THREE.Color("#2a78d6");

/** The objects a viewer page works with, shared with measuring, sections and markups (6.9, 6.10). */
export type ViewerHandle = {
  scene: THREE.Scene;
  camera: THREE.PerspectiveCamera;
  renderer: THREE.WebGLRenderer;
  controls: OrbitControls;
  model: THREE.Object3D;
  render: () => void;
  /** The model's size, for scaling markers. */
  radius: number;
};

/**
 * The web 3D viewer (roadmap 6.8): a model's glTF with its structure tree, orbit, standard views,
 * show, hide, isolate and select, and transparency. [children] get the viewer's handle to add tools.
 */
export function Viewer3D({
  url,
  title,
  onReady,
  tools,
  onPick,
}: {
  url: string;
  title: string;
  onReady?: (h: ViewerHandle) => void;
  tools?: (h: ViewerHandle) => React.ReactNode;
  /** A click on the model, with the point hit (model coordinates) and its normal; false to let selection happen. */
  onPick?: (hit: THREE.Intersection) => boolean;
}) {
  const host = useRef<HTMLDivElement>(null);
  const [handle, setHandle] = useState<ViewerHandle | null>(null);
  const [items, setItems] = useState<TreeItem[]>([]);
  const [hidden, setHidden] = useState<Set<string>>(new Set());
  const [selected, setSelected] = useState<string | null>(null);
  const [transparent, setTransparent] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const onPickRef = useRef(onPick);
  onPickRef.current = onPick;

  // Scene, camera, renderer, controls: once per model.
  useEffect(() => {
    const el = host.current!;
    const renderer = new THREE.WebGLRenderer({ antialias: true, preserveDrawingBuffer: true });
    renderer.setPixelRatio(window.devicePixelRatio);
    renderer.localClippingEnabled = true;
    el.appendChild(renderer.domElement);
    const scene = new THREE.Scene();
    const dark = matchMedia("(prefers-color-scheme: dark)").matches && document.documentElement.dataset.theme !== "light";
    scene.background = new THREE.Color(dark ? "#1b1f26" : "#f4f6f9");
    scene.add(new THREE.HemisphereLight(0xffffff, 0x8a8f99, 2.2));
    const sun = new THREE.DirectionalLight(0xffffff, 1.6);
    scene.add(sun);
    const camera = new THREE.PerspectiveCamera(40, 1, 0.0001, 1000);
    const controls = new OrbitControls(camera, renderer.domElement);
    controls.enableDamping = false;
    let frame = 0;
    const render = () => {
      cancelAnimationFrame(frame);
      frame = requestAnimationFrame(() => {
        sun.position.copy(camera.position);
        renderer.render(scene, camera);
      });
    };
    controls.addEventListener("change", render);
    const resize = () => {
      const w = el.clientWidth;
      const h = el.clientHeight;
      renderer.setSize(w, h);
      camera.aspect = w / Math.max(1, h);
      camera.updateProjectionMatrix();
      render();
    };
    const observer = new ResizeObserver(resize);
    observer.observe(el);
    let disposed = false;
    new GLTFLoader()
      .loadAsync(url)
      .then((gltf) => {
        if (disposed) return;
        const model = gltf.scene;
        // Each mesh gets its own material, so it can be highlighted alone.
        model.traverse((o) => {
          if (o instanceof THREE.Mesh) {
            const m = (o.material as THREE.MeshStandardMaterial).clone();
            m.userData.color = m.color.clone();
            o.material = m;
          }
        });
        scene.add(model);
        const box = new THREE.Box3().setFromObject(model);
        const sphere = box.getBoundingSphere(new THREE.Sphere());
        controls.target.copy(sphere.center);
        camera.near = sphere.radius / 100;
        camera.far = sphere.radius * 100;
        const h: ViewerHandle = { scene, camera, renderer, controls, model, render, radius: sphere.radius };
        setHandle(h);
        // The tree: named groups, depth first.
        const out: TreeItem[] = [];
        const walk = (o: THREE.Object3D, depth: number) => {
          const meshes: THREE.Mesh[] = [];
          o.traverse((c) => c instanceof THREE.Mesh && meshes.push(c));
          const named = !!o.name && !(o instanceof THREE.Mesh);
          if (named && meshes.length) out.push({ id: o.uuid, name: o.name.replace(/_/g, " "), depth, object: o, meshes });
          o.children.forEach((c) => walk(c, named ? depth + 1 : depth));
        };
        walk(model, 0);
        setItems(out);
        setView(h, "iso");
        onReady?.(h);
      })
      .catch((e: unknown) => setError(String(e)));
    return () => {
      disposed = true;
      observer.disconnect();
      controls.dispose();
      renderer.dispose();
      el.removeChild(renderer.domElement);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [url]);

  const setView = useCallback((h: ViewerHandle, view: View) => {
    const c = h.controls.target;
    const d = h.radius * 2.6;
    const dir = {
      iso: new THREE.Vector3(1, 0.8, 1),
      front: new THREE.Vector3(0, 0, 1),
      top: new THREE.Vector3(0, 1, 0.0001),
      right: new THREE.Vector3(1, 0, 0),
    }[view];
    h.camera.position.copy(c).add(dir.normalize().multiplyScalar(d));
    h.camera.lookAt(c);
    h.controls.update();
    h.render();
  }, []);

  const fit = useCallback(
    (objects?: THREE.Object3D[]) => {
      if (!handle) return;
      const box = new THREE.Box3();
      (objects ?? [handle.model]).forEach((o) => box.expandByObject(o));
      const s = box.getBoundingSphere(new THREE.Sphere());
      const dir = handle.camera.position.clone().sub(handle.controls.target).normalize();
      handle.controls.target.copy(s.center);
      handle.camera.position.copy(s.center).add(dir.multiplyScalar(Math.max(s.radius, handle.radius / 50) * 2.6));
      handle.controls.update();
      handle.render();
    },
    [handle],
  );

  // Looks: visibility, selection and transparency.
  useEffect(() => {
    if (!handle) return;
    const hiddenMeshes = new Set<THREE.Mesh>();
    items.filter((i) => hidden.has(i.id)).forEach((i) => i.meshes.forEach((m) => hiddenMeshes.add(m)));
    const chosen = new Set(items.find((i) => i.id === selected)?.meshes ?? []);
    handle.model.traverse((o) => {
      if (!(o instanceof THREE.Mesh)) return;
      o.visible = !hiddenMeshes.has(o);
      const m = o.material as THREE.MeshStandardMaterial;
      m.color.copy(chosen.has(o) ? SELECTED : (m.userData.color as THREE.Color));
      m.emissive.set(chosen.has(o) ? "#0b2a55" : "#000000");
      m.transparent = transparent && !chosen.has(o);
      m.opacity = m.transparent ? 0.25 : 1;
      m.depthWrite = !m.transparent;
    });
    handle.render();
  }, [handle, items, hidden, selected, transparent]);

  // Picking: the clicked part's nearest named group.
  useEffect(() => {
    if (!handle) return;
    const el = handle.renderer.domElement;
    let down = { x: 0, y: 0 };
    const onDown = (e: PointerEvent) => (down = { x: e.clientX, y: e.clientY });
    const onUp = (e: PointerEvent) => {
      if (Math.hypot(e.clientX - down.x, e.clientY - down.y) > 4) return; // a drag, not a click
      const r = el.getBoundingClientRect();
      const ray = new THREE.Raycaster();
      ray.setFromCamera(
        new THREE.Vector2(((e.clientX - r.left) / r.width) * 2 - 1, -((e.clientY - r.top) / r.height) * 2 + 1),
        handle.camera,
      );
      const hit = ray.intersectObject(handle.model, true).find((h) => h.object.visible && h.object instanceof THREE.Mesh);
      if (hit && onPickRef.current?.(hit)) return;
      if (!hit) return setSelected(null);
      let o: THREE.Object3D | null = hit.object;
      while (o && !items.some((i) => i.object === o)) o = o.parent;
      const item = items.find((i) => i.object === o);
      setSelected(item ? item.id : null);
    };
    el.addEventListener("pointerdown", onDown);
    el.addEventListener("pointerup", onUp);
    return () => {
      el.removeEventListener("pointerdown", onDown);
      el.removeEventListener("pointerup", onUp);
    };
  }, [handle, items]);

  const chosen = items.find((i) => i.id === selected);
  const isolate = () => {
    if (!chosen) return;
    const keep = new Set<string>([chosen.id]);
    // Ancestors and descendants of the chosen one stay.
    items.forEach((i) => {
      if (chosen.meshes.some((m) => i.meshes.includes(m))) keep.add(i.id);
    });
    setHidden(new Set(items.filter((i) => !keep.has(i.id) && !i.meshes.every((m) => chosen.meshes.includes(m))).map((i) => i.id)));
    fit([chosen.object]);
  };
  const toggle = (id: string) => setHidden((s) => (s.has(id) ? new Set([...s].filter((x) => x !== id)) : new Set([...s, id])));
  const stats = useMemo(() => {
    let meshes = 0;
    handle?.model.traverse((o) => o instanceof THREE.Mesh && meshes++);
    return meshes;
  }, [handle]);

  return (
    <div className="viewer3d">
      <div className="viewer-tree" aria-label={t("Model tree")}>
        <div className="viewer-tree-head">
          <strong>{title}</strong>
          <span className="muted small">{t("{n} bodies", { n: stats })}</span>
        </div>
        <ul>
          {items.map((i) => (
            <li key={i.id} className={i.id === selected ? "selected" : ""} style={{ paddingLeft: 6 + i.depth * 14 }}>
              <button className="icon-btn" aria-label={hidden.has(i.id) ? t("Show") : t("Hide")} onClick={() => toggle(i.id)}>
                {hidden.has(i.id) ? <EyeOff size={13} /> : <Eye size={13} />}
              </button>
              <button
                className="link-btn tree-name"
                onClick={() => setSelected(i.id === selected ? null : i.id)}
                onDoubleClick={() => fit([i.object])}
              >
                {i.name}
              </button>
            </li>
          ))}
        </ul>
      </div>
      <div className="viewer-main">
        <div className="viewer-toolbar" role="toolbar" aria-label={t("View")}>
          <button className="btn small ghost" onClick={() => fit()} title={t("Fit all")}>
            <Maximize2 size={13} aria-hidden /> {t("Fit")}
          </button>
          {(["iso", "front", "top", "right"] as View[]).map((v) => (
            <button key={v} className="btn small ghost" disabled={!handle} onClick={() => handle && setView(handle, v)}>
              {v === "iso" ? t("Iso") : v === "front" ? t("Front") : v === "top" ? t("Top") : t("Right")}
            </button>
          ))}
          <span className="sep" />
          <button
            className={`btn small ghost ${transparent ? "on" : ""}`}
            aria-pressed={transparent}
            onClick={() => setTransparent(!transparent)}
          >
            <Layers size={13} aria-hidden /> {t("See through")}
          </button>
          <button className="btn small ghost" disabled={!chosen} onClick={isolate}>
            <Focus size={13} aria-hidden /> {t("Isolate")}
          </button>
          <button className="btn small ghost" disabled={!chosen} onClick={() => chosen && fit([chosen.object])}>
            <ScanEye size={13} aria-hidden /> {t("Zoom to")}
          </button>
          <button className="btn small ghost" disabled={!hidden.size} onClick={() => setHidden(new Set())}>
            <Box size={13} aria-hidden /> {t("Show all")}
          </button>
          {handle && tools?.(handle)}
        </div>
        <div className="viewer-canvas" ref={host}>
          {!handle && !error && <div className="viewer-loading">{t("Loading the model…")}</div>}
          {error && <div className="banner error">{t("The model couldn't be shown: {error}", { error })}</div>}
          {chosen && <div className="viewer-chip">{chosen.name}</div>}
        </div>
      </div>
    </div>
  );
}
