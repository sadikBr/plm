import { useMutation, useQueryClient } from "@tanstack/react-query";
import {
  ChevronLeft,
  ChevronRight,
  Cloud,
  MessageSquare,
  MousePointer2,
  MoveUpRight,
  Plus,
  Square,
  Trash2,
  Type,
  ZoomIn,
  ZoomOut,
} from "lucide-react";
import * as pdfjs from "pdfjs-dist";
import workerUrl from "pdfjs-dist/build/pdf.worker.min.mjs?url";
import { useEffect, useRef, useState } from "react";
import { Link } from "react-router";
import { api, authorizedFetch, unwrap } from "../api/client";
import { useMe } from "../api/queries";
import type { components } from "../api/schema";
import { useComments } from "../collaboration/Collaboration";
import { ErrorBanner, PrincipalName, formatDate } from "../components/common";
import { fileLink } from "../components/ObjectFiles";
import { config } from "../config";
import { t, tn } from "../i18n/i18n";
import { useMarkups } from "./Markups3D";

pdfjs.GlobalWorkerOptions.workerSrc = workerUrl;

type Markup = components["schemas"]["Markup"];
/** Shapes in page units (PDF points, origin top left), whatever the zoom. */
export type Shape =
  | { type: "arrow"; x1: number; y1: number; x2: number; y2: number }
  | { type: "rect" | "cloud"; x: number; y: number; w: number; h: number }
  | { type: "text"; x: number; y: number; text: string };
type Tool = "select" | "arrow" | "rect" | "cloud" | "text";

/** A scalloped outline around a box: the usual revision cloud. */
function cloudPath(x: number, y: number, w: number, h: number): string {
  const r = Math.max(4, Math.min(w, h) / 8);
  const edge = (x0: number, y0: number, x1: number, y1: number) => {
    const len = Math.hypot(x1 - x0, y1 - y0);
    const n = Math.max(1, Math.round(len / (r * 2)));
    let d = "";
    for (let i = 1; i <= n; i++) d += ` A ${len / n / 2} ${len / n / 2} 0 0 1 ${x0 + ((x1 - x0) * i) / n} ${y0 + ((y1 - y0) * i) / n}`;
    return d;
  };
  return `M ${x} ${y}${edge(x, y, x + w, y)}${edge(x + w, y, x + w, y + h)}${edge(x + w, y + h, x, y + h)}${edge(x, y + h, x, y)} Z`;
}

function ShapeView({ s, n }: { s: Shape; n: number }) {
  switch (s.type) {
    case "arrow": {
      const a = Math.atan2(s.y2 - s.y1, s.x2 - s.x1);
      const head = (d: number) => `${s.x2 - 12 * Math.cos(a + d)},${s.y2 - 12 * Math.sin(a + d)}`;
      return (
        <g>
          <line x1={s.x1} y1={s.y1} x2={s.x2} y2={s.y2} />
          <polygon points={`${s.x2},${s.y2} ${head(0.4)} ${head(-0.4)}`} className="fill" />
        </g>
      );
    }
    case "rect":
      return <rect x={s.x} y={s.y} width={s.w} height={s.h} />;
    case "cloud":
      return <path d={cloudPath(s.x, s.y, s.w, s.h)} />;
    case "text":
      return (
        <g>
          <circle cx={s.x} cy={s.y} r={9} className="fill" />
          <text x={s.x} y={s.y + 4} className="num">
            {n}
          </text>
          {s.text && (
            <text x={s.x + 14} y={s.y + 5} className="label">
              {s.text}
            </text>
          )}
        </g>
      );
  }
}

/** The file's bytes: watermarked for this viewer when the site says so (roadmap 6.7). */
async function loadPdf(contentId: string, watermark: boolean): Promise<ArrayBuffer> {
  if (watermark) {
    const r = await authorizedFetch(`/api/v1/content/${contentId}/watermarked`);
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    return r.arrayBuffer();
  }
  const url = await fileLink(contentId, true);
  const r = await fetch(url.startsWith("/") ? config.apiBase + url : url);
  if (!r.ok) throw new Error(`HTTP ${r.status}`);
  return r.arrayBuffer();
}

/**
 * The 2D viewer (roadmap 6.11): a PDF (drawing, document) rendered in the browser, page by page,
 * with zoom; markups of arrows, rectangles, clouds and numbered notes saved per version and page.
 */
export function SheetViewer({
  objectId,
  contentId,
  fileName,
  watermark,
}: {
  objectId: string;
  contentId: string;
  fileName: string;
  watermark: boolean;
}) {
  const qc = useQueryClient();
  const { data: me } = useMe();
  const { data: allMarkups } = useMarkups(objectId, "SHEET");
  const { data: comments } = useComments(objectId);
  const markups = allMarkups?.filter((m) => m.contentId === contentId) ?? [];
  const [doc, setDoc] = useState<pdfjs.PDFDocumentProxy | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [page, setPage] = useState(1);
  const [zoom, setZoom] = useState<number | "fit">("fit");
  const [size, setSize] = useState<{ w: number; h: number } | null>(null);
  const [scale, setScale] = useState(1);
  const [tool, setTool] = useState<Tool>("select");
  const [shown, setShown] = useState<Markup | null>(null);
  const [draft, setDraft] = useState<{ title: string; note: string; shapes: Shape[] } | null>(null);
  const [drag, setDrag] = useState<{ x: number; y: number; x2: number; y2: number } | null>(null);
  const canvas = useRef<HTMLCanvasElement>(null);
  const area = useRef<HTMLDivElement>(null);

  useEffect(() => {
    let task: pdfjs.PDFDocumentLoadingTask | null = null;
    loadPdf(contentId, watermark)
      .then((data) => {
        task = pdfjs.getDocument({ data });
        return task.promise;
      })
      .then(setDoc)
      .catch((e: unknown) => setError(String(e)));
    return () => void task?.destroy();
  }, [contentId, watermark]);

  // Draws the page, sharp at any zoom.
  useEffect(() => {
    if (!doc) return;
    let render: pdfjs.RenderTask | null = null;
    let cancelled = false;
    void doc.getPage(page).then((p) => {
      if (cancelled) return;
      const base = p.getViewport({ scale: 1 });
      const fit = Math.max(0.1, ((area.current?.clientWidth ?? 800) - 32) / base.width);
      const s = zoom === "fit" ? fit : zoom;
      setScale(s);
      setSize({ w: base.width, h: base.height });
      const vp = p.getViewport({ scale: s });
      const dpr = window.devicePixelRatio || 1;
      const c = canvas.current!;
      c.width = Math.floor(vp.width * dpr);
      c.height = Math.floor(vp.height * dpr);
      c.style.width = `${vp.width}px`;
      c.style.height = `${vp.height}px`;
      render = p.render({ canvasContext: c.getContext("2d")!, viewport: vp, transform: [dpr, 0, 0, dpr, 0, 0] });
      render.promise.catch(() => {});
    });
    return () => {
      cancelled = true;
      render?.cancel();
    };
  }, [doc, page, zoom]);

  const refresh = () => {
    void qc.invalidateQueries({ queryKey: ["markups", objectId] });
    void qc.invalidateQueries({ queryKey: ["comments", objectId] });
  };
  const save = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/api/v1/objects/{id}/markups", {
          params: { path: { id: objectId } },
          body: {
            kind: "SHEET",
            contentId,
            page,
            title: draft!.title,
            note: draft!.note || undefined,
            view: { zoom: scale },
            shapes: draft!.shapes as unknown as Record<string, unknown>[],
          },
        }),
      ),
    onSuccess: (m) => {
      setDraft(null);
      setTool("select");
      setShown(m);
      refresh();
    },
  });
  const remove = useMutation({
    mutationFn: (id: string) => unwrap(api.DELETE("/api/v1/markups/{markupId}", { params: { path: { markupId: id } } })),
    onSuccess: () => {
      setShown(null);
      refresh();
    },
  });

  // Drawing: pointer positions in page units.
  const at = (e: React.PointerEvent<SVGSVGElement>) => {
    const r = e.currentTarget.getBoundingClientRect();
    return { x: (e.clientX - r.left) / scale, y: (e.clientY - r.top) / scale };
  };
  const add = (s: Shape) => setDraft((d) => (d ? { ...d, shapes: [...d.shapes, s] } : d));
  const onDown = (e: React.PointerEvent<SVGSVGElement>) => {
    if (!draft || tool === "select") return;
    const p = at(e);
    if (tool === "text") return add({ type: "text", x: p.x, y: p.y, text: "" });
    e.currentTarget.setPointerCapture(e.pointerId);
    setDrag({ x: p.x, y: p.y, x2: p.x, y2: p.y });
  };
  const onMove = (e: React.PointerEvent<SVGSVGElement>) => drag && setDrag({ ...drag, ...((p) => ({ x2: p.x, y2: p.y }))(at(e)) });
  const onUp = () => {
    if (!drag) return;
    const { x, y, x2, y2 } = drag;
    setDrag(null);
    if (Math.hypot(x2 - x, y2 - y) < 4) return;
    if (tool === "arrow") add({ type: "arrow", x1: x, y1: y, x2, y2 });
    else if (tool === "rect" || tool === "cloud")
      add({ type: tool, x: Math.min(x, x2), y: Math.min(y, y2), w: Math.abs(x2 - x), h: Math.abs(y2 - y) });
  };
  const preview: Shape | null = drag
    ? tool === "arrow"
      ? { type: "arrow", x1: drag.x, y1: drag.y, x2: drag.x2, y2: drag.y2 }
      : {
          type: tool === "cloud" ? "cloud" : "rect",
          x: Math.min(drag.x, drag.x2),
          y: Math.min(drag.y, drag.y2),
          w: Math.abs(drag.x2 - drag.x),
          h: Math.abs(drag.y2 - drag.y),
        }
    : null;

  const shapes: Shape[] = draft ? draft.shapes : shown && shown.page === page ? (shown.shapes as unknown as Shape[]) : [];
  const show = (m: Markup) => {
    setDraft(null);
    setShown(m);
    setPage(m.page ?? 1);
  };
  const replies = (m: Markup) => comments?.filter((c) => c.parentId === m.topicId && !c.deleted).length ?? 0;
  const latest = allMarkups?.at(-1)?.version;
  const pages = doc?.numPages ?? 0;
  const tools: [Tool, React.ReactNode, string][] = [
    ["select", <MousePointer2 size={13} aria-hidden />, t("Pan")],
    ["arrow", <MoveUpRight size={13} aria-hidden />, t("Arrow")],
    ["rect", <Square size={13} aria-hidden />, t("Rectangle")],
    ["cloud", <Cloud size={13} aria-hidden />, t("Cloud")],
    ["text", <Type size={13} aria-hidden />, t("Note")],
  ];
  const zoomBy = (f: number) => setZoom(Math.min(8, Math.max(0.2, scale * f)));

  return (
    <div className="sheet-viewer">
      <div className="viewer-toolbar" role="toolbar" aria-label={t("View")}>
        <button className="icon-btn" aria-label={t("Previous page")} disabled={page <= 1} onClick={() => setPage(page - 1)}>
          <ChevronLeft size={15} />
        </button>
        <span className="small">{pages ? t("Page {page} of {pages}", { page, pages }) : "…"}</span>
        <button className="icon-btn" aria-label={t("Next page")} disabled={page >= pages} onClick={() => setPage(page + 1)}>
          <ChevronRight size={15} />
        </button>
        <span className="sep" />
        <button className="icon-btn" aria-label={t("Zoom out")} onClick={() => zoomBy(1 / 1.25)}>
          <ZoomOut size={15} />
        </button>
        <span className="small zoom-value">{Math.round(scale * 100)}%</span>
        <button className="icon-btn" aria-label={t("Zoom in")} onClick={() => zoomBy(1.25)}>
          <ZoomIn size={15} />
        </button>
        <button className={`btn small ghost ${zoom === "fit" ? "on" : ""}`} onClick={() => setZoom("fit")}>
          {t("Fit width")}
        </button>
        {draft && (
          <>
            <span className="sep" />
            {tools.map(([k, icon, label]) => (
              <button key={k} className={`btn small ghost ${tool === k ? "on" : ""}`} aria-pressed={tool === k} onClick={() => setTool(k)}>
                {icon} {label}
              </button>
            ))}
          </>
        )}
        <span className="muted small sheet-file">{fileName}</span>
      </div>
      <div className="sheet-body">
        <div className="sheet-area" ref={area}>
          {error && <div className="banner error">{t("The file couldn't be shown: {error}", { error })}</div>}
          {!doc && !error && <div className="viewer-loading">{t("Loading the file…")}</div>}
          <div className="sheet-page" style={size ? { width: size.w * scale, height: size.h * scale } : undefined}>
            <canvas ref={canvas} />
            {size && (
              <svg
                className={`sheet-marks ${draft && tool !== "select" ? "drawing" : ""}`}
                viewBox={`0 0 ${size.w} ${size.h}`}
                width={size.w * scale}
                height={size.h * scale}
                onPointerDown={onDown}
                onPointerMove={onMove}
                onPointerUp={onUp}
                aria-label={t("Markups on the page")}
              >
                {shapes.map((s, i) => (
                  <ShapeView key={i} s={s} n={shapes.slice(0, i + 1).filter((x) => x.type === "text").length} />
                ))}
                {preview && <ShapeView s={preview} n={0} />}
              </svg>
            )}
          </div>
        </div>
        <aside className="sheet-panel" aria-label={t("Markups")}>
          <h3>{t("Markups")}</h3>
          {draft ? (
            <div className="markup-draft">
              <label className="field">
                <span>{t("Title")}</span>
                <input value={draft.title} autoFocus onChange={(e) => setDraft({ ...draft, title: e.target.value })} />
              </label>
              <label className="field">
                <span>{t("Note")}</span>
                <textarea rows={2} value={draft.note} onChange={(e) => setDraft({ ...draft, note: e.target.value })} />
              </label>
              <p className="muted small">{t("Draw on page {page} with the tools above.", { page })}</p>
              <ol className="pin-list">
                {draft.shapes.map((s, i) => (
                  <li key={i}>
                    {s.type === "text" ? (
                      <input
                        aria-label={t("Note {n}", { n: i + 1 })}
                        placeholder={t("What's here?")}
                        value={s.text}
                        onChange={(e) =>
                          setDraft({ ...draft, shapes: draft.shapes.map((x, j) => (j === i ? { ...s, text: e.target.value } : x)) })
                        }
                      />
                    ) : (
                      <span className="shape-name">
                        {s.type === "arrow" ? t("Arrow") : s.type === "rect" ? t("Rectangle") : t("Cloud")}
                      </span>
                    )}
                    <button
                      className="icon-btn"
                      aria-label={t("Remove")}
                      onClick={() => setDraft({ ...draft, shapes: draft.shapes.filter((_, j) => j !== i) })}
                    >
                      <Trash2 size={13} />
                    </button>
                  </li>
                ))}
              </ol>
              {save.error && <ErrorBanner error={save.error} />}
              <div className="row">
                <button className="btn small primary" disabled={!draft.title.trim() || save.isPending} onClick={() => save.mutate()}>
                  {t("Save markup")}
                </button>
                <button
                  className="btn small ghost"
                  onClick={() => {
                    setDraft(null);
                    setTool("select");
                  }}
                >
                  {t("Cancel")}
                </button>
              </div>
            </div>
          ) : (
            <>
              <button
                className="btn small"
                disabled={!doc}
                onClick={() => {
                  setShown(null);
                  setDraft({ title: "", note: "", shapes: [] });
                  setTool("cloud");
                }}
              >
                <Plus size={13} aria-hidden /> {t("New markup")}
              </button>
              {!markups.length && <p className="muted small">{t("No markups yet.")}</p>}
              <ul className="markup-list">
                {[...markups].reverse().map((m) => (
                  <li key={m.id} className={shown?.id === m.id ? "selected" : ""}>
                    <button className="link-btn markup-title" onClick={() => show(m)}>
                      {m.title}
                    </button>
                    <span className="muted small">
                      <PrincipalName id={m.author} /> · {formatDate(m.createdAt)} · {t("page {page}", { page: m.page ?? 1 })}
                      {m.version !== latest && <> · {t("on {version}", { version: m.version })}</>}
                    </span>
                    <span className="row small">
                      <Link to={`/objects/${objectId}?tab=discussion`} className="muted">
                        <MessageSquare size={12} aria-hidden /> {tn("{n} reply", "{n} replies", replies(m))}
                      </Link>
                      {(m.author === me?.user.id || me?.siteAdmin) && (
                        <button className="link-btn danger" onClick={() => remove.mutate(m.id)}>
                          {t("Delete")}
                        </button>
                      )}
                    </span>
                  </li>
                ))}
              </ul>
            </>
          )}
        </aside>
      </div>
    </div>
  );
}
