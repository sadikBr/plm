import { useMutation } from "@tanstack/react-query";
import { CheckCircle2, Download, FileArchive, FileSpreadsheet, FolderPlus, Play, TriangleAlert } from "lucide-react";
import { useState } from "react";
import { ApiError, authorizedFetch, type Schemas } from "../api/client";
import { useTypes } from "../api/queries";
import { Dialog, ErrorBanner, Loading, typeLabel } from "../components/common";
import { fetchPdf } from "../publishing/Publishing";
import { t, tn } from "../i18n/i18n";

type Preview = Schemas["LoadPreview"];
type Row = Schemas["LoadRow"];

async function send<T>(path: string, sheet: File, archive: File | null): Promise<T> {
  const form = new FormData();
  form.append("sheet", sheet, sheet.name);
  if (archive) form.append("files", archive, archive.name);
  const r = await authorizedFetch(path, { method: "POST", body: form });
  const body = await r.json().catch(() => undefined);
  if (!r.ok) throw new ApiError(r.status, body as never);
  return body as T;
}

const ACTION_CLASS: Record<string, string> = { CREATE: "tag allow", UPDATE: "tag accent", UNCHANGED: "tag", ERROR: "tag deny" };

function actionLabel(a: string) {
  switch (a) {
    case "CREATE":
      return t("New");
    case "UPDATE":
      return t("Update");
    case "UNCHANGED":
      return t("Unchanged");
    default:
      return t("Error");
  }
}

/** Bulk load (roadmap 9.2): a spreadsheet (and a zip of its files), the dry run row by row, then the load as a job. */
export function BulkLoadDialog({
  contextId,
  onClose,
  onStarted,
}: {
  contextId: string;
  onClose: () => void;
  onStarted: (jobId: string) => void;
}) {
  const [sheet, setSheet] = useState<File | null>(null);
  const [archive, setArchive] = useState<File | null>(null);
  const [onlyProblems, setOnlyProblems] = useState(false);
  const [templateType, setTemplateType] = useState("00000000-0000-7000-8000-000000000102");
  const { data: types } = useTypes();
  const preview = useMutation({
    mutationFn: ({ s, a }: { s: File; a: File | null }) => send<Preview>(`/api/v1/bulk-loads/preview?contextId=${contextId}`, s, a),
  });
  const start = useMutation({
    mutationFn: () => send<Schemas["Job"]>(`/api/v1/bulk-loads?contextId=${contextId}`, sheet!, archive),
    onSuccess: (job) => {
      onStarted(job.id);
      onClose();
    },
  });
  const check = (s: File | null, a: File | null) => {
    if (s) preview.mutate({ s, a });
  };
  const p = preview.data;
  const rows: Row[] = (p?.rows ?? []).filter((r) => !onlyProblems || r.errors.length > 0 || r.warnings.length > 0);
  const creatable = (types ?? []).filter((ty) => ty.instantiable && (ty.baseClass === "PART" || ty.baseClass === "DOCUMENT"));
  return (
    <Dialog
      open
      wide
      title={t("Load from a spreadsheet")}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            {t("Cancel")}
          </button>
          <button
            className="btn primary"
            disabled={!p || p.errors > 0 || p.creates + p.updates + p.usages + p.links === 0 || start.isPending}
            onClick={() => start.mutate()}
          >
            <Play size={14} aria-hidden /> {t("Load")}
          </button>
        </>
      }
    >
      <ErrorBanner error={preview.error ?? start.error} />
      <div className="load-pickers">
        <label className="file-drop small">
          <input
            type="file"
            accept=".csv,.xlsx"
            aria-label={t("Spreadsheet")}
            onChange={(e) => {
              const f = e.target.files?.[0] ?? null;
              setSheet(f);
              check(f, archive);
            }}
          />
          <FileSpreadsheet size={20} aria-hidden />
          <span>{sheet ? sheet.name : t("Choose the spreadsheet (.xlsx or .csv)")}</span>
        </label>
        <label className="file-drop small">
          <input
            type="file"
            accept=".zip"
            aria-label={t("Files (zip)")}
            onChange={(e) => {
              const f = e.target.files?.[0] ?? null;
              setArchive(f);
              check(sheet, f);
            }}
          />
          <FileArchive size={20} aria-hidden />
          <span>{archive ? archive.name : t("Optional: a .zip with the files its File column names")}</span>
        </label>
      </div>
      {!p && !preview.isPending && (
        <div className="load-help">
          <p className="muted">
            {t(
              "One row per object. Type and Name (or Number) are needed; Folder, File, Parent with Quantity and Find number, Documents and State are optional; every other column is an attribute, by name or label. A number that exists updates that object.",
            )}
          </p>
          <div className="row">
            <select aria-label={t("Template type")} value={templateType} onChange={(e) => setTemplateType(e.target.value)}>
              {creatable.map((ty) => (
                <option key={ty.id} value={ty.id}>
                  {typeLabel(ty)}
                </option>
              ))}
            </select>
            <button
              className="btn small"
              onClick={() => void fetchPdf(`/api/v1/bulk-loads/template?typeId=${templateType}`, "bulk-load.csv")}
            >
              <Download size={13} aria-hidden /> {t("Download a template")}
            </button>
          </div>
        </div>
      )}
      {preview.isPending && <Loading />}
      {p && (
        <>
          <div className={p.errors ? "banner error" : "banner ok"}>
            {p.errors ? <TriangleAlert size={15} aria-hidden /> : <CheckCircle2 size={15} aria-hidden />}{" "}
            {p.errors
              ? tn(
                  "{n} row has errors: fix the sheet and choose it again.",
                  "{n} rows have errors: fix the sheet and choose it again.",
                  p.errors,
                )
              : t("Ready to load into {context}.", { context: p.contextName })}
          </div>
          <div className="load-summary">
            <span>
              <strong>{p.creates}</strong> {t("new")}
            </span>
            <span>
              <strong>{p.updates}</strong> {t("updated")}
            </span>
            <span>
              <strong>{p.files}</strong> {t("files")}
            </span>
            <span>
              <strong>{p.usages}</strong> {t("structure lines")}
            </span>
            <span>
              <strong>{p.links}</strong> {t("document links")}
            </span>
            {p.newFolders.length > 0 && (
              <span title={p.newFolders.join("\n")}>
                <FolderPlus size={13} aria-hidden /> <strong>{p.newFolders.length}</strong> {t("new folders")}
              </span>
            )}
            <label className="check" style={{ marginLeft: "auto" }}>
              <input type="checkbox" checked={onlyProblems} onChange={(e) => setOnlyProblems(e.target.checked)} />
              {t("Only rows with remarks")}
            </label>
          </div>
          <div className="load-table">
            <table className="grid" aria-label={t("Rows")}>
              <thead>
                <tr>
                  <th className="num">{t("Row")}</th>
                  <th>{t("Action")}</th>
                  <th>{t("Type")}</th>
                  <th>{t("Number")}</th>
                  <th>{t("Name")}</th>
                  <th>{t("Folder")}</th>
                  <th>{t("Under")}</th>
                  <th>{t("Remarks")}</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.row} className={r.errors.length ? "row-error" : undefined}>
                    <td className="num muted">{r.row}</td>
                    <td>
                      <span className={ACTION_CLASS[r.action] ?? "tag"}>{actionLabel(r.action)}</span>
                    </td>
                    <td className="small">{r.type ?? "—"}</td>
                    <td className="small nowrap">{r.number ?? <span className="muted">{t("automatic")}</span>}</td>
                    <td>
                      {r.name}
                      {r.file && <div className="muted small">{r.file}</div>}
                    </td>
                    <td className="small muted">{r.folder ?? ""}</td>
                    <td className="small nowrap">{r.parent ?? ""}</td>
                    <td className="small">
                      {r.errors.map((e) => (
                        <div key={e} className="load-error">
                          {e}
                        </div>
                      ))}
                      {r.warnings.map((w) => (
                        <div key={w} className="muted">
                          {w}
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
    </Dialog>
  );
}
