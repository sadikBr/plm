import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Pencil, Plus, Trash2 } from "lucide-react";
import { useState } from "react";
import { Link } from "react-router";
import { api, unwrap, type Schemas } from "../api/client";
import { useContextsOfKind, useTypes } from "../api/queries";
import { Dialog, ErrorBanner, Loading, Panel, PrincipalName, formatDate, typeLabel } from "../components/common";
import { t } from "../i18n/i18n";

type Template = Schemas["DocumentTemplate"];

/**
 * Document templates (roadmap 3.3): objects new documents start from. Each is an ordinary object,
 * versioned and access-controlled like any other; its latest version is what gets copied.
 */
export function DocumentTemplatesAdmin() {
  const qc = useQueryClient();
  const {
    data: templates,
    isLoading,
    error,
  } = useQuery({
    queryKey: ["documentTemplates", "all"],
    queryFn: () => unwrap(api.GET("/api/v1/document-templates")),
  });
  const { data: types } = useTypes();
  const { data: orgs } = useContextsOfKind("ORGANIZATION");
  const remove = useMutation({
    mutationFn: (id: string) => unwrap(api.DELETE("/api/v1/document-templates/{id}", { params: { path: { id } } })),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["documentTemplates"] }),
  });
  const [editing, setEditing] = useState<Template | "new" | null>(null);
  const typeName = (id: string) => {
    const type = types?.find((x) => x.id === id);
    return type ? typeLabel(type) : "…";
  };

  if (isLoading) return <Loading />;
  return (
    <Panel
      title={t("Document templates")}
      note={t("New documents can start from these: their type, values and files (latest version)")}
      flush
      actions={
        <button className="btn small primary" onClick={() => setEditing("new")}>
          <Plus size={13} aria-hidden />
          {t("New template")}
        </button>
      }
    >
      <ErrorBanner error={error ?? remove.error} />
      {!templates?.length ? (
        <p className="empty">{t("No templates yet. Make a document the way new ones should start, then register it here.")}</p>
      ) : (
        <table className="grid" aria-label={t("Document templates")}>
          <thead>
            <tr>
              <th>{t("Template")}</th>
              <th>{t("Object")}</th>
              <th>{t("Type")}</th>
              <th>{t("File")}</th>
              <th>{t("Offered in")}</th>
              <th>{t("Updated")}</th>
              <th aria-label={t("Actions")} />
            </tr>
          </thead>
          <tbody>
            {templates.map((tpl) => (
              <tr key={tpl.id} className={tpl.active ? undefined : "inactive"}>
                <td>
                  <strong>{tpl.name}</strong>
                  {tpl.description && <div className="muted small">{tpl.description}</div>}
                  {!tpl.active && <div className="muted small">{t("Off")}</div>}
                </td>
                <td className="nowrap">
                  <Link to={`/objects/${tpl.objectId}`}>{tpl.number}</Link> <span className="tag accent version">{tpl.version}</span>
                </td>
                <td>{typeName(tpl.typeId)}</td>
                <td>{tpl.fileName ?? <span className="muted">{t("No file")}</span>}</td>
                <td>
                  {tpl.organizationId ? (orgs?.find((o) => o.id === tpl.organizationId)?.name ?? t("An organization")) : t("Everywhere")}
                </td>
                <td className="nowrap muted">
                  {formatDate(tpl.updatedAt)} · <PrincipalName id={tpl.updatedBy} />
                </td>
                <td className="nowrap">
                  <button className="icon-btn" aria-label={t("Edit {name}", { name: tpl.name })} onClick={() => setEditing(tpl)}>
                    <Pencil size={13} />
                  </button>
                  <button
                    className="icon-btn"
                    aria-label={t("Delete {name}", { name: tpl.name })}
                    onClick={() =>
                      window.confirm(t("Delete the template “{name}”? Its object stays.", { name: tpl.name })) && remove.mutate(tpl.id)
                    }
                  >
                    <Trash2 size={13} />
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {editing && <TemplateDialog template={editing === "new" ? null : editing} onClose={() => setEditing(null)} />}
    </Panel>
  );
}

function TemplateDialog({ template, onClose }: { template: Template | null; onClose: () => void }) {
  const qc = useQueryClient();
  const { data: orgs } = useContextsOfKind("ORGANIZATION");
  const [name, setName] = useState(template?.name ?? "");
  const [description, setDescription] = useState(template?.description ?? "");
  const [organizationId, setOrganizationId] = useState(template?.organizationId ?? "");
  const [active, setActive] = useState(template?.active ?? true);
  const [query, setQuery] = useState(template?.number ?? "");
  const [objectId, setObjectId] = useState(template?.objectId ?? "");
  const { data: matches } = useQuery({
    queryKey: ["templateFind", query],
    queryFn: () => unwrap(api.GET("/api/v1/objects", { params: { query: { q: query.trim(), limit: 8 } } })),
    enabled: query.trim().length >= 2 && !template,
  });
  const save = useMutation({
    mutationFn: () => {
      const body = { objectId, name, description: description || null, organizationId: organizationId || null, active };
      return template
        ? unwrap(api.PUT("/api/v1/document-templates/{id}", { params: { path: { id: template.id } }, body }))
        : unwrap(api.POST("/api/v1/document-templates", { body }));
    },
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["documentTemplates"] });
      onClose();
    },
  });
  return (
    <Dialog
      open
      title={template ? t("Edit {name}", { name: template.name }) : t("New document template")}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            {t("Cancel")}
          </button>
          <button className="btn primary" disabled={save.isPending || !name.trim() || !objectId} onClick={() => save.mutate()}>
            {t("Save")}
          </button>
        </>
      }
    >
      <div className="attr-form">
        <ErrorBanner error={save.error} />
        <div className="field wide">
          <label htmlFor="tpl-name">{t("Name")}</label>
          <input id="tpl-name" value={name} onChange={(e) => setName(e.target.value)} autoFocus placeholder={t("Test report")} />
        </div>
        <div className="field wide">
          <label htmlFor="tpl-object">{t("Template object")}</label>
          <input
            id="tpl-object"
            value={query}
            disabled={!!template}
            onChange={(e) => {
              setQuery(e.target.value);
              setObjectId("");
            }}
            placeholder={t("Number or name of the object to start from")}
          />
          {!template && (
            <div className="pick-list" role="listbox" aria-label={t("Matching objects")}>
              {matches?.map((m) => (
                <button
                  key={m.id}
                  role="option"
                  aria-selected={objectId === m.id}
                  className={objectId === m.id ? "selected" : undefined}
                  onClick={() => setObjectId(m.id)}
                >
                  <strong>{m.number}</strong> {m.name} <span className="muted">{m.version}</span>
                </button>
              ))}
            </div>
          )}
        </div>
        <div className="field wide">
          <label htmlFor="tpl-desc">{t("Description")}</label>
          <input id="tpl-desc" value={description} onChange={(e) => setDescription(e.target.value)} />
        </div>
        <div className="field wide">
          <label htmlFor="tpl-org">{t("Offered in")}</label>
          <select id="tpl-org" value={organizationId} onChange={(e) => setOrganizationId(e.target.value)}>
            <option value="">{t("Every organization")}</option>
            {orgs?.map((o) => (
              <option key={o.id} value={o.id}>
                {t("{name} only", { name: o.name })}
              </option>
            ))}
          </select>
        </div>
        <label className="check wide">
          <input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} />
          {t("Offered when creating")}
        </label>
      </div>
    </Dialog>
  );
}
