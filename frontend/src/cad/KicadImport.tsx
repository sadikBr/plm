import { useMutation, useQuery } from "@tanstack/react-query";
import { CheckCircle2, CircuitBoard, Cpu, FileUp, TriangleAlert } from "lucide-react";
import { useState } from "react";
import { Link } from "react-router";
import { ApiError, authorizedFetch, type Schemas } from "../api/client";
import { Dialog, ErrorBanner, Loading, Panel } from "../components/common";
import { t, tn } from "../i18n/i18n";

type Preview = Schemas["EcadPreview"];
type Result = Schemas["EcadImportResult"];

async function send<T>(path: string, files: File[]): Promise<T> {
  const form = new FormData();
  files.forEach((f) => form.append("files", f, f.name));
  const r = await authorizedFetch(path, { method: "POST", body: form });
  const body = await r.json().catch(() => undefined);
  if (!r.ok) throw new ApiError(r.status, body as never);
  return body as T;
}

/** KiCad files a project is made of. */
const KICAD = /\.(kicad_pro|kicad_sch|kicad_pcb)$|^(sym|fp)-lib-table$/;

/** Imports a KiCad project (roadmap 8.7): its documents, and the board assembly's structure from the schematic. */
export function KicadImportDialog({ contextId, onClose }: { contextId: string; onClose: () => void }) {
  const [files, setFiles] = useState<File[]>([]);
  const [createParts, setCreateParts] = useState(true);
  const preview = useMutation({ mutationFn: (chosen: File[]) => send<Preview>("/api/v1/ecad/kicad/preview", chosen) });
  const run = useMutation({
    mutationFn: () => send<Result>(`/api/v1/ecad/kicad/import?contextId=${contextId}&createParts=${createParts}`, files),
  });
  const choose = (list: FileList | null) => {
    const chosen = [...(list ?? [])].filter((f) => KICAD.test(f.name));
    setFiles(chosen);
    if (chosen.length) preview.mutate(chosen);
  };
  const p = preview.data;
  const done = run.data;
  return (
    <Dialog
      open
      wide
      title={t("Import a KiCad project")}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            {done ? t("Close") : t("Cancel")}
          </button>
          {!done && (
            <button className="btn primary" disabled={!p || run.isPending} onClick={() => run.mutate()}>
              <FileUp size={14} aria-hidden /> {t("Import")}
            </button>
          )}
        </>
      }
    >
      <ErrorBanner error={preview.error ?? run.error} />
      {done ? (
        <div className="kicad-done">
          <div className="banner ok">
            <CheckCircle2 size={15} aria-hidden />{" "}
            {t("Imported: {n} documents and the assembly with {lines} lines.", { n: done.documents.length + 1, lines: done.lines })}
          </div>
          <ul className="kicad-links">
            {[done.project, ...done.documents, done.assembly].map((d) => (
              <li key={d.id}>
                <Link to={`/objects/${d.id}`} onClick={onClose}>
                  <strong>{d.number}</strong> {d.name}
                </Link>
              </li>
            ))}
          </ul>
          {done.createdParts.length > 0 && (
            <p className="muted">{tn("{n} new part was made.", "{n} new parts were made.", done.createdParts.length)}</p>
          )}
        </div>
      ) : (
        <>
          <label className="file-drop">
            <input
              type="file"
              multiple
              aria-label={t("KiCad project files")}
              onChange={(e) => choose(e.target.files)}
              // A whole project folder at once, where the browser allows it.
              {...({ webkitdirectory: "" } as object)}
            />
            <CircuitBoard size={22} aria-hidden />
            <span>
              {files.length
                ? tn("{n} KiCad file chosen", "{n} KiCad files chosen", files.length)
                : t("Choose the project folder (its .kicad_pro, schematics and board)")}
            </span>
          </label>
          {preview.isPending && <Loading />}
          {p && (
            <div className="kicad-preview">
              <div className="kicad-facts">
                <span>
                  <Cpu size={14} aria-hidden /> <strong>{p.project}</strong>
                </span>
                <span>{tn("{n} sheet", "{n} sheets", p.sheets.length)}</span>
                {p.board && (
                  <span>
                    {t("Board {w} × {h} mm, {layers} copper layers, {n} footprints", {
                      w: Math.round(p.width),
                      h: Math.round(p.height),
                      layers: p.copperLayers,
                      n: p.footprints,
                    })}
                  </span>
                )}
              </div>
              <Panel title={t("Bill of materials")} note={tn("{n} line", "{n} lines", p.lines.length)} flush>
                <div className="kicad-bom">
                  <table className="grid" aria-label={t("Bill of materials")}>
                    <thead>
                      <tr>
                        <th>{t("Qty")}</th>
                        <th>{t("Value")}</th>
                        <th>{t("Footprint")}</th>
                        <th>{t("References")}</th>
                        <th>{t("Part")}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {p.lines.map((l) => (
                        <tr key={l.references.join()}>
                          <td className="num">{l.references.length}</td>
                          <td>{l.value}</td>
                          <td className="muted small">{l.footprint.split(":").pop()}</td>
                          <td className="small">{l.references.join(", ")}</td>
                          <td className="small">{l.partLabel ?? <span className="tag">{createParts ? t("new") : t("none")}</span>}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </Panel>
              <label className="check">
                <input type="checkbox" checked={createParts} onChange={(e) => setCreateParts(e.target.checked)} />
                {t("Make a part for each line no existing part matches (by part number or name)")}
              </label>
            </div>
          )}
        </>
      )}
    </Dialog>
  );
}

const ECAD_TYPES = new Set(["00000000-0000-7000-8000-000000000110", "00000000-0000-7000-8000-000000000112"]);

/** On an ECAD project or board: does the schematic's BOM match the assembly's structure? */
export function EcadBomCheck({ objectId, typeId }: { objectId: string; typeId: string }) {
  const { data, error, isLoading } = useQuery({
    queryKey: ["ecadCheck", objectId],
    queryFn: async () => {
      const r = await authorizedFetch(`/api/v1/objects/${objectId}/ecad/bom-check`);
      if (!r.ok) throw new ApiError(r.status, (await r.json().catch(() => undefined)) as never);
      return (await r.json()) as Schemas["BomCheck"];
    },
    enabled: ECAD_TYPES.has(typeId),
  });
  if (!ECAD_TYPES.has(typeId)) return null;
  const kinds: Record<string, string> = {
    MISSING: t("Not in the structure"),
    EXTRA: t("Not in the schematic"),
    QUANTITY: t("Quantity differs"),
    DESIGNATORS: t("References differ"),
  };
  return (
    <Panel
      title={t("BOM check")}
      note={data?.assembly ? t("The schematic against {part}", { part: `${data.assembly.number} ${data.assembly.name}` }) : undefined}
      flush
    >
      <ErrorBanner error={error} />
      {isLoading ? (
        <Loading />
      ) : !data?.assembly ? (
        <p className="empty">{t("No part is owned by this board yet.")}</p>
      ) : data.differences.length === 0 ? (
        <div className="banner ok bom-ok">
          <CheckCircle2 size={15} aria-hidden />{" "}
          {tn("The structure matches the schematic: {n} line.", "The structure matches the schematic: {n} lines.", data.lines)}
        </div>
      ) : (
        <table className="grid" aria-label={t("BOM check")}>
          <thead>
            <tr>
              <th>{t("Difference")}</th>
              <th>{t("Part")}</th>
              <th>{t("In the schematic")}</th>
              <th>{t("In the structure")}</th>
            </tr>
          </thead>
          <tbody>
            {data.differences.map((d, i) => (
              <tr key={i}>
                <td className="nowrap">
                  <TriangleAlert size={13} className="warn-icon" aria-hidden /> {kinds[d.kind] ?? d.kind}
                </td>
                <td>{d.label}</td>
                <td className="small">{d.schematic.join(", ") || "—"}</td>
                <td className="small">{d.structure.join(", ") || "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Panel>
  );
}
