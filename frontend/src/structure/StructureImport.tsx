import { useMutation } from "@tanstack/react-query";
import { FileUp, TriangleAlert } from "lucide-react";
import { useState } from "react";
import { api, unwrap, type Schemas } from "../api/client";
import type { VersionedObject } from "../api/queries";
import { Dialog, ErrorBanner } from "../components/common";
import { t, tn } from "../i18n/i18n";

type Preview = Schemas["ImportPreview"];

/**
 * Structure import (roadmap 4.13): choose a CSV or Excel file, see what it would do line by line
 * (which parts exist, which are new, what's wrong), then run it in the background.
 */
export function ImportStructureButton({ obj, onStarted }: { obj: VersionedObject; onStarted: (jobId: string) => void }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button className="btn small" onClick={() => setOpen(true)}>
        <FileUp size={13} aria-hidden /> {t("Import")}
      </button>
      {open && (
        <ImportDialog
          obj={obj}
          onClose={() => setOpen(false)}
          onStarted={(id) => {
            setOpen(false);
            onStarted(id);
          }}
        />
      )}
    </>
  );
}

function ImportDialog({ obj, onClose, onStarted }: { obj: VersionedObject; onClose: () => void; onStarted: (jobId: string) => void }) {
  const [file, setFile] = useState<File | null>(null);
  const preview = useMutation({
    mutationFn: async (f: File) =>
      unwrap(
        api.POST("/api/v1/objects/{id}/structure-import/preview", {
          params: { path: { id: obj.id }, query: { fileName: f.name } },
          body: (await f.arrayBuffer()) as never,
          bodySerializer: (b: unknown) => b as BodyInit,
          headers: { "Content-Type": "application/octet-stream" },
        }),
      ) as Promise<Preview>,
  });
  const start = useMutation({
    mutationFn: (p: Preview) =>
      unwrap(
        api.POST("/api/v1/objects/{id}/structure-import", {
          params: { path: { id: obj.id } },
          body: { fileName: file?.name ?? null, rows: p.rows.map((r) => r.row) },
        }),
      ),
    onSuccess: (job) => onStarted(job.id),
  });
  const p = preview.data;
  return (
    <Dialog
      open
      wide
      title={t("Import a structure into {number}", { number: obj.number })}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            {t("Cancel")}
          </button>
          <button className="btn primary" disabled={!p || p.errors > 0 || start.isPending} onClick={() => p && start.mutate(p)}>
            {t("Import")}
          </button>
        </>
      }
    >
      <div style={{ display: "grid", gap: 12 }}>
        <p className="muted" style={{ margin: 0 }}>
          {t(
            "A CSV or Excel file with a Level column (1: used by {number}) and a Number or Name column; Quantity, Unit, Find no., Reference designators and Notes are optional. Parts are found by number; those not found are created. Each assembly in the file gets exactly the lines under it.",
            { number: obj.number },
          )}
        </p>
        <div className="field">
          <label htmlFor="import-file">{t("File")}</label>
          <input
            id="import-file"
            type="file"
            accept=".csv,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            onChange={(e) => {
              const f = e.target.files?.[0] ?? null;
              setFile(f);
              if (f) preview.mutate(f);
            }}
          />
        </div>
        <ErrorBanner error={preview.error ?? start.error} />
        {preview.isPending && <div className="muted">{t("Reading the file…")}</div>}
        {p && (
          <>
            <div className={p.errors > 0 ? "banner error" : "banner info"} role="status">
              {tn("{n} line", "{n} lines", p.rows.length)} · {tn("{n} new part", "{n} new parts", p.creates)} ·{" "}
              {tn("{n} structure to set", "{n} structures to set", p.assemblies)}
              {p.errors > 0 && <> · {tn("{n} line to fix", "{n} lines to fix", p.errors)}</>}
            </div>
            <div className="import-preview">
              <table className="grid" aria-label={t("Import preview")}>
                <thead>
                  <tr>
                    <th className="num">{t("Row")}</th>
                    <th className="num">{t("Level")}</th>
                    <th>{t("Number")}</th>
                    <th>{t("Name")}</th>
                    <th className="num">{t("Quantity")}</th>
                    <th>{t("Unit")}</th>
                    <th>{t("Find no.")}</th>
                    <th>{t("Reference designators")}</th>
                    <th>{t("Part")}</th>
                    <th>{t("Problems")}</th>
                  </tr>
                </thead>
                <tbody>
                  {p.rows.map((r) => (
                    <tr key={r.row.row} className={r.errors.length ? "row-error" : undefined}>
                      <td className="num muted">{r.row.row}</td>
                      <td className="num">{r.row.level}</td>
                      <td className="mono" style={{ paddingLeft: 8 + Math.max(0, r.row.level - 1) * 14 }}>
                        {r.partNumber ?? r.row.number ?? ""}
                      </td>
                      <td>
                        {r.partName ?? r.row.name}
                        {r.assembly && <span className="tag">{t("assembly")}</span>}
                      </td>
                      <td className="num">{r.row.quantity ?? 1}</td>
                      <td>{r.row.unit ?? ""}</td>
                      <td>{r.row.findNumber ?? ""}</td>
                      <td>{r.row.designators ?? ""}</td>
                      <td>
                        {r.action === "MATCH" && <span className="tag allow">{t("existing")}</span>}
                        {r.action === "CREATE" && <span className="tag accent">{t("new")}</span>}
                      </td>
                      <td>
                        {r.errors.map((e) => (
                          <div key={e} className="error-text">
                            {e}
                          </div>
                        ))}
                        {r.warnings.map((w) => (
                          <div key={w} className="muted small">
                            <TriangleAlert size={11} aria-hidden /> {w}
                          </div>
                        ))}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
      </div>
    </Dialog>
  );
}
