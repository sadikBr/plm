import { useMutation } from "@tanstack/react-query";
import { useState } from "react";
import { useNavigate } from "react-router";
import { authorizedFetch, ApiError, type Schemas } from "../api/client";
import { Dialog, ErrorBanner } from "../components/common";
import { t } from "../i18n/i18n";

type Preview = Schemas["StepPreview"];
type Result = Schemas["StepImportResult"];

async function post<T>(path: string, file: File): Promise<T> {
  const r = await authorizedFetch(path, { method: "POST", headers: { "Content-Type": "application/octet-stream" }, body: file });
  const body = (await r.json().catch(() => undefined)) as unknown;
  if (!r.ok) throw new ApiError(r.status, body as never);
  return body as T;
}

const mm = (v: number) => (Math.abs(v) < 1e-6 ? "0" : Number(v.toFixed(2)).toString());

/** STEP import (roadmap 6.5): a STEP file's assembly, checked first, then made into CAD documents. */
export function StepImportDialog({ contextId, onClose }: { contextId: string; onClose: () => void }) {
  const navigate = useNavigate();
  const [file, setFile] = useState<File | null>(null);
  const [createParts, setCreateParts] = useState(true);
  const [build, setBuild] = useState(true);
  const preview = useMutation({
    mutationFn: (f: File) => post<Preview>(`/api/v1/cad/step-import/preview?fileName=${encodeURIComponent(f.name)}`, f),
  });
  const run = useMutation({
    mutationFn: () =>
      post<Result>(
        `/api/v1/cad/step-import?fileName=${encodeURIComponent(file!.name)}&contextId=${contextId}&createParts=${createParts}&build=${createParts && build}`,
        file!,
      ),
    onSuccess: (r) => navigate(`/objects/${r.rootId}?tab=cad`),
  });
  const p = preview.data;
  return (
    <Dialog
      open
      wide
      title={t("Import a STEP file")}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            {t("Cancel")}
          </button>
          <button className="btn primary" disabled={!p || run.isPending} onClick={() => run.mutate()}>
            {run.isPending ? t("Importing…") : p ? t("Import {n} models", { n: p.models }) : t("Import")}
          </button>
        </>
      }
    >
      <div className="field">
        <label htmlFor="step-file">{t("STEP file (.stp, .step; AP203, AP214, AP242)")}</label>
        <input
          id="step-file"
          type="file"
          accept=".stp,.step,.p21"
          onChange={(e) => {
            const f = e.target.files?.[0] ?? null;
            setFile(f);
            if (f) preview.mutate(f);
          }}
        />
      </div>
      <ErrorBanner error={preview.error ?? run.error} />
      {p && (
        <>
          <p>
            <strong>{p.fileName}</strong> · {p.schema} ·{" "}
            {t("{models} models, {assemblies} of them assemblies", { models: p.models, assemblies: p.assemblies })} ·{" "}
            {p.unitToMm === 1 ? t("millimetres") : t("{n} mm per unit", { n: p.unitToMm })}
          </p>
          <div className="step-preview">
            <table className="grid" aria-label={t("STEP structure")}>
              <thead>
                <tr>
                  <th>{t("Model")}</th>
                  <th>{t("Occurrence")}</th>
                  <th className="num">{t("Position (mm)")}</th>
                </tr>
              </thead>
              <tbody>
                {p.lines.slice(0, 300).map((l, i) => (
                  <tr key={i}>
                    <td>
                      <span style={{ paddingLeft: l.depth * 18 }}>
                        <span className="tag">{l.kind === "ASSEMBLY" ? t("assembly") : t("part")}</span> {l.name}
                      </span>
                    </td>
                    <td className="mono">{l.occurrence ?? ""}</td>
                    <td className="num mono">{l.position ? l.position.map(mm).join(", ") : ""}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <label className="check">
            <input type="checkbox" checked={createParts} onChange={(e) => setCreateParts(e.target.checked)} />
            {t("Make a part for each model, owned by it")}
          </label>
          <label className="check" style={{ marginLeft: 22 }}>
            <input type="checkbox" checked={createParts && build} disabled={!createParts} onChange={(e) => setBuild(e.target.checked)} />
            {t("Build the assembly parts' structures from their models")}
          </label>
          <p className="hint">{t("The file goes with the top model; each model is a CAD document, numbered as usual.")}</p>
        </>
      )}
    </Dialog>
  );
}
