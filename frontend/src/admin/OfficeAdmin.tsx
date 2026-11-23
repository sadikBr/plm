import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckCircle2, Info, Plus, Trash2 } from "lucide-react";
import { useState } from "react";
import { api, unwrap, type Schemas } from "../api/client";
import { useTypes } from "../api/queries";
import { ErrorBanner, Loading, Panel, typeLabel } from "../components/common";
import { t } from "../i18n/i18n";

type Mapping = Schemas["PropertyMapping"];

/** The core properties every Office file has (custom ones are named freely). */
const CORE = (): [string, string][] => [
  ["title", t("Title")],
  ["subject", t("Subject")],
  ["author", t("Author")],
  ["keywords", t("Keywords")],
  ["description", t("Comments")],
  ["category", t("Category")],
  ["status", t("Status")],
];

/** Which Office file properties fill which attributes, per document type (roadmap 8.4). */
export function OfficeAdmin() {
  const { data: types } = useTypes();
  const documentTypes = (types ?? []).filter((ty) => ty.baseClass === "DOCUMENT");
  const [typeId, setTypeId] = useState("00000000-0000-7000-8000-000000000101");
  const { data: status } = useQuery({ queryKey: ["office", "status"], queryFn: () => unwrap(api.GET("/api/v1/office/status")) });
  return (
    <div className="stack">
      <div className={status?.previews ? "banner ok" : "banner info"}>
        {status?.previews ? <CheckCircle2 size={15} aria-hidden /> : <Info size={15} aria-hidden />}{" "}
        {status?.previews
          ? t("LibreOffice is installed: Word, Excel and PowerPoint files get page previews and thumbnails.")
          : t("Install LibreOffice on the server for previews of Word, Excel and PowerPoint files.")}
      </div>
      <Panel
        title={t("Office file properties")}
        note={t(
          "When a Word, Excel or PowerPoint file becomes a document's file, these properties fill its attributes; downloads carry the attributes back.",
        )}
        actions={
          <select aria-label={t("Type")} value={typeId} onChange={(e) => setTypeId(e.target.value)}>
            {documentTypes.map((ty) => (
              <option key={ty.id} value={ty.id}>
                {typeLabel(ty)}
              </option>
            ))}
          </select>
        }
        flush
      >
        <MappingEditor key={typeId} typeId={typeId} />
      </Panel>
    </div>
  );
}

function MappingEditor({ typeId }: { typeId: string }) {
  const qc = useQueryClient();
  const { data, isLoading } = useQuery({
    queryKey: ["office", "mappings", typeId],
    queryFn: () => unwrap(api.GET("/api/v1/office/mappings/{typeId}", { params: { path: { typeId } } })),
  });
  const { data: attributes } = useQuery({
    queryKey: ["typeAttributes", typeId],
    queryFn: () => unwrap(api.GET("/api/v1/types/{typeId}/attributes", { params: { path: { typeId } } })),
  });
  const [draft, setDraft] = useState<Mapping[] | null>(null);
  const rows = draft ?? data ?? [];
  const save = useMutation({
    mutationFn: () =>
      unwrap(api.PUT("/api/v1/office/mappings/{typeId}", { params: { path: { typeId } }, body: rows.filter((m) => !m.inherited) })),
    onSuccess: () => {
      setDraft(null);
      void qc.invalidateQueries({ queryKey: ["office", "mappings"] });
    },
  });
  if (isLoading) return <Loading />;
  const edit = (i: number, change: Partial<Mapping>) => setDraft(rows.map((m, j) => (j === i ? { ...m, ...change } : m)));
  const coreNames = new Set(CORE().map(([k]) => k));
  return (
    <>
      <table className="grid" aria-label={t("Office file properties")}>
        <thead>
          <tr>
            <th>{t("Property in the file")}</th>
            <th>{t("Attribute")}</th>
            <th aria-label={t("Actions")} />
          </tr>
        </thead>
        <tbody>
          {rows.map((m, i) => (
            <tr key={i} className={m.inherited ? "muted-row" : undefined}>
              <td>
                {m.inherited ? (
                  <span>
                    {CORE().find(([k]) => k === m.property)?.[1] ?? m.property} <span className="tag">{t("inherited")}</span>
                  </span>
                ) : (
                  <span className="row office-prop">
                    <select
                      aria-label={t("Property {n}", { n: i + 1 })}
                      value={coreNames.has(m.property) ? m.property : "__custom"}
                      onChange={(e) => edit(i, { property: e.target.value === "__custom" ? "" : e.target.value })}
                    >
                      {CORE().map(([k, label]) => (
                        <option key={k} value={k}>
                          {label}
                        </option>
                      ))}
                      <option value="__custom">{t("A custom property…")}</option>
                    </select>
                    {!coreNames.has(m.property) && (
                      <input
                        aria-label={t("Custom property name {n}", { n: i + 1 })}
                        placeholder={t("e.g. Department")}
                        value={m.property}
                        onChange={(e) => edit(i, { property: e.target.value })}
                      />
                    )}
                  </span>
                )}
              </td>
              <td>
                {m.inherited ? (
                  <code>{m.attribute}</code>
                ) : (
                  <select
                    aria-label={t("Attribute {n}", { n: i + 1 })}
                    value={m.attribute}
                    onChange={(e) => edit(i, { attribute: e.target.value })}
                  >
                    <option value="">{t("Choose…")}</option>
                    {(attributes ?? []).map((a) => (
                      <option key={a.name} value={a.name}>
                        {a.labels?.en ?? a.name} ({a.name})
                      </option>
                    ))}
                  </select>
                )}
              </td>
              <td>
                {!m.inherited && (
                  <button
                    className="btn small ghost"
                    aria-label={t("Remove mapping {n}", { n: i + 1 })}
                    onClick={() => setDraft(rows.filter((_, j) => j !== i))}
                  >
                    <Trash2 size={13} aria-hidden />
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="panel-foot">
        <button className="btn" onClick={() => setDraft([...rows, { property: "title", attribute: "", inherited: false }])}>
          <Plus size={14} aria-hidden /> {t("Add a mapping")}
        </button>
        <span className="grow" />
        <ErrorBanner error={save.error} />
        {draft && (
          <>
            <button className="btn" onClick={() => setDraft(null)}>
              {t("Cancel")}
            </button>
            <button
              className="btn primary"
              disabled={save.isPending || rows.some((m) => !m.property.trim() || !m.attribute)}
              onClick={() => save.mutate()}
            >
              {t("Save")}
            </button>
          </>
        )}
      </div>
    </>
  );
}
