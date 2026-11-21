import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Puzzle } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router";
import { api, authorizedFetch, unwrap, type Schemas } from "../api/client";
import { useMe, type VersionedObject } from "../api/queries";
import { Panel } from "../components/common";
import { language, t } from "../i18n/i18n";

type Slot = Schemas["UiSlot"];

export const useUiSlots = (objectId: string) =>
  useQuery({
    queryKey: ["uiSlots", objectId],
    queryFn: () => unwrap(api.GET("/api/v1/objects/{id}/ui-extensions", { params: { path: { id: objectId } } })),
    staleTime: 60_000,
  });

const SAFE_PATH = /^[A-Za-z0-9][A-Za-z0-9/_\-.~%]*(\?[A-Za-z0-9_\-.~%=&+]*)?$/;

/**
 * An extension's panel or tab (roadmap 5.15): its page in a sandboxed frame, and the message bridge.
 * The page posts `plm:ready` and is sent `plm:context` (object, user, language, theme); it may post
 * `plm:resize` {height}, `plm:navigate` {to}, `plm:refresh`, and `plm:fetch` {id, path, method, body}
 * for its own extension's endpoints, answered by `plm:response` {id, status, body}.
 */
export function ExtensionFrame({ slot, obj }: { slot: Slot; obj: VersionedObject }) {
  const frame = useRef<HTMLIFrameElement>(null);
  const [height, setHeight] = useState(slot.height);
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { data: me } = useMe();
  // A frame served from the PLM's own origin would escape the sandbox with allow-same-origin.
  const ownOrigin = slot.origin === window.location.origin;

  useEffect(() => {
    const target = ownOrigin ? "*" : slot.origin;
    const reply = (message: unknown) => frame.current?.contentWindow?.postMessage(message, target);
    const onMessage = (e: MessageEvent) => {
      if (e.source !== frame.current?.contentWindow) return;
      if (!ownOrigin && e.origin !== slot.origin) return;
      const m = e.data as { type?: unknown; [k: string]: unknown } | null;
      if (!m || typeof m.type !== "string" || !m.type.startsWith("plm:")) return;
      switch (m.type) {
        case "plm:ready":
          reply({
            type: "plm:context",
            object: {
              id: obj.id,
              number: obj.number,
              name: obj.name,
              typeId: obj.typeId,
              state: obj.latest.state,
              version: `${obj.latest.revision}.${obj.latest.iteration}`,
              contextId: obj.contextId,
            },
            user: me ? { id: me.user.id, username: me.user.username, name: me.user.displayName } : null,
            language: language(),
            theme: document.documentElement.dataset.theme ?? (matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light"),
          });
          break;
        case "plm:resize":
          setHeight(Math.min(2000, Math.max(60, Number(m.height) || slot.height)));
          break;
        case "plm:navigate":
          if (typeof m.to === "string" && m.to.startsWith("/") && !m.to.startsWith("//")) navigate(m.to);
          break;
        case "plm:refresh":
          void qc.invalidateQueries({ queryKey: ["object", obj.id] });
          break;
        case "plm:fetch": {
          const id = m.id;
          const path = String(m.path ?? "");
          const method = String(m.method ?? "GET").toUpperCase();
          if (!SAFE_PATH.test(path) || path.split(/[/?]/).includes("..") || !["GET", "POST", "PUT", "DELETE"].includes(method)) {
            reply({ type: "plm:response", id, status: 400, body: { message: "Not a path of this extension" } });
            break;
          }
          void authorizedFetch(`/api/v1/ext/${encodeURIComponent(slot.extensionId)}/${path}`, {
            method,
            headers: { "Content-Type": "application/json" },
            body: method === "GET" || m.body === undefined ? undefined : JSON.stringify(m.body),
          })
            .then(async (r) => {
              const text = await r.text();
              let body: unknown = text;
              try {
                body = JSON.parse(text);
              } catch {
                /* not JSON: pass the text */
              }
              reply({ type: "plm:response", id, status: r.status, body });
            })
            .catch(() => reply({ type: "plm:response", id, status: 0, body: { message: "The call failed" } }));
          break;
        }
      }
    };
    window.addEventListener("message", onMessage);
    return () => window.removeEventListener("message", onMessage);
  }, [slot, obj, me, navigate, qc, ownOrigin]);

  return (
    <iframe
      ref={frame}
      className="ext-frame"
      title={`${slot.extensionName}: ${slot.title}`}
      src={slot.url}
      style={{ height }}
      sandbox={ownOrigin ? "allow-scripts allow-forms" : "allow-scripts allow-forms allow-popups allow-same-origin"}
      referrerPolicy="no-referrer"
    />
  );
}

/** The extension panels for the Details tab. */
export function ExtensionPanels({ obj }: { obj: VersionedObject }) {
  const { data } = useUiSlots(obj.id);
  const panels = (data ?? []).filter((s) => s.slot === "object.panel");
  return (
    <>
      {panels.map((s) => (
        <Panel
          key={`${s.extensionId}/${s.id}`}
          title={s.title}
          note={
            <span className="ext-source">
              <Puzzle size={12} aria-hidden /> {t("from {extension}", { extension: s.extensionName })}
            </span>
          }
          flush
        >
          <ExtensionFrame slot={s} obj={obj} />
        </Panel>
      ))}
    </>
  );
}

/** An extension's tab. */
export function ExtensionTab({ slot, obj }: { slot: Slot; obj: VersionedObject }) {
  return (
    <Panel
      title={slot.title}
      note={
        <span className="ext-source">
          <Puzzle size={12} aria-hidden /> {t("from {extension}", { extension: slot.extensionName })}
        </span>
      }
      flush
    >
      <ExtensionFrame slot={slot} obj={obj} />
    </Panel>
  );
}
