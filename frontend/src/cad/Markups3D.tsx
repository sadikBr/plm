import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { MapPin, MessageSquare, Plus, Trash2, X } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { Link } from "react-router";
import * as THREE from "three";
import { api, unwrap } from "../api/client";
import { useMe } from "../api/queries";
import type { components } from "../api/schema";
import { useComments } from "../collaboration/Collaboration";
import { ErrorBanner, PrincipalName, formatDate } from "../components/common";
import { t, tn } from "../i18n/i18n";
import type { ViewState, ViewerHandle } from "./Viewer3D";

type Markup = components["schemas"]["Markup"];
/** A pin: a point of the model (its own coordinates) with a note. */
type Pin = { at: number[]; text: string };

export const useMarkups = (objectId: string, kind: "MODEL" | "SHEET") =>
  useQuery({
    queryKey: ["markups", objectId, kind],
    queryFn: () => unwrap(api.GET("/api/v1/objects/{id}/markups", { params: { path: { id: objectId }, query: { kind } } })),
  });

/** Numbered pins over the model, following the camera. */
function Pins({ h, pins }: { h: ViewerHandle; pins: Pin[] }) {
  const refs = useRef<(HTMLDivElement | null)[]>([]);
  useEffect(() => {
    const points = pins.map((p) => new THREE.Vector3().fromArray(p.at).applyMatrix4(h.fromModel));
    const place = () => {
      // The camera may have moved since the last frame drew.
      h.camera.updateMatrixWorld();
      points.forEach((pt, i) => {
        const el = refs.current[i];
        if (!el) return;
        const s = pt.clone().project(h.camera);
        const r = h.renderer.domElement;
        el.style.left = `${((s.x + 1) / 2) * r.clientWidth}px`;
        el.style.top = `${((1 - s.y) / 2) * r.clientHeight}px`;
        el.style.display = s.z > 1 ? "none" : "";
      });
    };
    place();
    const frame = requestAnimationFrame(place);
    h.controls.addEventListener("change", place);
    return () => {
      cancelAnimationFrame(frame);
      h.controls.removeEventListener("change", place);
    };
  }, [h, pins]);
  return createPortal(
    <>
      {pins.map((p, i) => (
        <div key={i} ref={(el) => void (refs.current[i] = el)} className="pin" title={p.text}>
          <span>{i + 1}</span>
          {p.text && <em>{p.text}</em>}
        </div>
      ))}
    </>,
    h.overlay,
  );
}

/**
 * 3D markups and saved views (roadmap 6.10): the camera, hidden parts and see-through saved with
 * numbered pins and notes, on the model's latest version; each discussed in the object's discussion.
 */
export function Markups3D({ h, objectId, onClose }: { h: ViewerHandle; objectId: string; onClose: () => void }) {
  const qc = useQueryClient();
  const { data: me } = useMe();
  const { data: markups, error } = useMarkups(objectId, "MODEL");
  const { data: comments } = useComments(objectId);
  const [shown, setShown] = useState<Markup | null>(null);
  const [draft, setDraft] = useState<{ title: string; note: string; pins: Pin[] } | null>(null);
  const [pinning, setPinning] = useState(false);
  const refresh = () => {
    void qc.invalidateQueries({ queryKey: ["markups", objectId] });
    void qc.invalidateQueries({ queryKey: ["comments", objectId] });
  };

  useEffect(() => {
    if (!pinning) return;
    h.setPicker((hit) => {
      const at = hit.point.clone().applyMatrix4(h.toModel).toArray();
      setDraft((d) => (d ? { ...d, pins: [...d.pins, { at, text: "" }] } : d));
      setPinning(false);
    });
    return () => h.setPicker(null);
  }, [h, pinning]);

  const save = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/api/v1/objects/{id}/markups", {
          params: { path: { id: objectId } },
          body: {
            kind: "MODEL",
            title: draft!.title,
            note: draft!.note || undefined,
            view: h.snapshot() as unknown as Record<string, unknown>,
            shapes: draft!.pins as unknown as Record<string, unknown>[],
          },
        }),
      ),
    onSuccess: (m) => {
      setDraft(null);
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

  const show = (m: Markup) => {
    setDraft(null);
    setShown(m);
    h.restore(m.view as unknown as ViewState);
  };
  const replies = (m: Markup) => comments?.filter((c) => c.parentId === m.topicId && !c.deleted).length ?? 0;
  const latest = markups?.at(-1)?.version;
  const pins: Pin[] = draft ? draft.pins : ((shown?.shapes ?? []) as unknown as Pin[]);

  return (
    <>
      <Pins h={h} pins={pins} />
      {createPortal(
        <section className="viewer-panel" aria-label={t("Markups")}>
          <div className="row spread">
            <h3>{t("Markups")}</h3>
            <button className="icon-btn" aria-label={t("Close")} onClick={onClose}>
              <X size={14} />
            </button>
          </div>
          {error && <ErrorBanner error={error} />}
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
              <ol className="pin-list">
                {draft.pins.map((p, i) => (
                  <li key={i}>
                    <input
                      aria-label={t("Pin {n}", { n: i + 1 })}
                      placeholder={t("What's here?")}
                      value={p.text}
                      onChange={(e) =>
                        setDraft({ ...draft, pins: draft.pins.map((x, j) => (j === i ? { ...x, text: e.target.value } : x)) })
                      }
                    />
                    <button
                      className="icon-btn"
                      aria-label={t("Remove")}
                      onClick={() => setDraft({ ...draft, pins: draft.pins.filter((_, j) => j !== i) })}
                    >
                      <Trash2 size={13} />
                    </button>
                  </li>
                ))}
              </ol>
              <button className={`btn small ghost ${pinning ? "on" : ""}`} onClick={() => setPinning(!pinning)}>
                <MapPin size={13} aria-hidden /> {pinning ? t("Click the model…") : t("Add a pin")}
              </button>
              <p className="muted small">{t("The current camera, hidden parts and see-through are saved with it.")}</p>
              {save.error && <ErrorBanner error={save.error} />}
              <div className="row">
                <button className="btn small primary" disabled={!draft.title.trim() || save.isPending} onClick={() => save.mutate()}>
                  {t("Save markup")}
                </button>
                <button className="btn small ghost" onClick={() => setDraft(null)}>
                  {t("Cancel")}
                </button>
              </div>
            </div>
          ) : (
            <>
              <button
                className="btn small"
                onClick={() => {
                  setShown(null);
                  setDraft({ title: "", note: "", pins: [] });
                }}
              >
                <Plus size={13} aria-hidden /> {t("New markup")}
              </button>
              {markups && !markups.length && <p className="muted small">{t("No markups yet.")}</p>}
              <ul className="markup-list">
                {[...(markups ?? [])].reverse().map((m) => (
                  <li key={m.id} className={shown?.id === m.id ? "selected" : ""}>
                    <button className="link-btn markup-title" onClick={() => show(m)}>
                      {m.title}
                    </button>
                    <span className="muted small">
                      <PrincipalName id={m.author} /> · {formatDate(m.createdAt)}
                      {m.version !== latest && <> · {t("on {version}", { version: m.version })}</>}
                      {(m.shapes?.length ?? 0) > 0 && <> · {tn("{n} pin", "{n} pins", m.shapes.length)}</>}
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
        </section>,
        h.overlay,
      )}
    </>
  );
}
