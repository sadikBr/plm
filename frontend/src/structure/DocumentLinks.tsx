import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowUpCircle, Lock, Plus, Trash2, X } from "lucide-react";
import { useState } from "react";
import { Link } from "react-router";
import { api, unwrap, type Schemas } from "../api/client";
import { useObjectSearch, useTypes, type VersionedObject } from "../api/queries";
import { ErrorBanner, Loading, Panel, StateBadge, TypeIcon, typeLabel } from "../components/common";
import { t } from "../i18n/i18n";

// Part-document links (roadmap 4.7).

type DocumentLink = Schemas["DocumentLink"];
type Kind = DocumentLink["kind"];

const kindLabel = (k: Kind) => (k === "DESCRIBED_BY" ? t("Described by") : t("References"));

/** A part's Documents tab: the documents describing it (a version) and those it references (latest). */
export function DocumentLinksTab({ obj, editable }: { obj: VersionedObject; editable: boolean }) {
  const qc = useQueryClient();
  const { data, error, isLoading } = useQuery({
    queryKey: ["documentLinks", obj.id, editable],
    queryFn: () =>
      unwrap(api.GET("/api/v1/objects/{id}/document-links", { params: { path: { id: obj.id }, query: { workingCopy: editable } } })),
  });
  const refresh = () => void qc.invalidateQueries({ queryKey: ["documentLinks", obj.id] });
  const unlink = useMutation({
    mutationFn: (linkId: string) =>
      unwrap(api.DELETE("/api/v1/objects/{id}/document-links/{linkId}", { params: { path: { id: obj.id, linkId } } })),
    onSuccess: refresh,
  });
  const update = useMutation({
    mutationFn: (linkId: string) =>
      unwrap(api.POST("/api/v1/objects/{id}/document-links/{linkId}/update-to-latest", { params: { path: { id: obj.id, linkId } } })),
    onSuccess: refresh,
  });
  if (isLoading) return <Loading />;
  const section = (kind: Kind, note: string) => {
    const rows = (data ?? []).filter((l) => l.kind === kind);
    return (
      <Panel title={kindLabel(kind)} note={note} flush>
        <table className="grid" aria-label={kindLabel(kind)}>
          <thead>
            <tr>
              <th className="icon-cell" />
              <th>{t("Number")}</th>
              <th>{t("Name")}</th>
              <th>{t("Version")}</th>
              <th>{t("State")}</th>
              {editable && <th />}
            </tr>
          </thead>
          <tbody>
            {rows.map((l) => (
              <tr key={l.id}>
                <td className="icon-cell">
                  {l.document.typeId ? <TypeIcon typeId={l.document.typeId} /> : <Lock size={14} className="icon-muted" />}
                </td>
                {l.document.readable ? (
                  <>
                    <td className="nowrap">
                      <Link className="num-link" to={`/objects/${l.document.id}`}>
                        {l.document.number}
                      </Link>
                    </td>
                    <td>{l.document.name}</td>
                    <td className="version nowrap">
                      {l.document.version}
                      {l.newerExists && <span className="tag warn small-tag">{t("newer exists")}</span>}
                    </td>
                    <td>{l.document.state && <StateBadge state={l.document.state} />}</td>
                  </>
                ) : (
                  <td colSpan={4} className="muted">
                    {t("A document you don't have access to")}
                  </td>
                )}
                {editable && (
                  <td className="nowrap" style={{ width: 1 }}>
                    {l.newerExists && (
                      <button
                        className="icon-btn"
                        aria-label={t("Use the latest version of {number}", { number: l.document.number ?? "" })}
                        onClick={() => update.mutate(l.id)}
                      >
                        <ArrowUpCircle size={14} />
                      </button>
                    )}
                    <button
                      className="icon-btn"
                      aria-label={t("Unlink {number}", { number: l.document.number ?? "" })}
                      onClick={() => unlink.mutate(l.id)}
                    >
                      <Trash2 size={13} />
                    </button>
                  </td>
                )}
              </tr>
            ))}
            {rows.length === 0 && (
              <tr>
                <td colSpan={editable ? 6 : 5} className="empty">
                  {t("None.")}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </Panel>
    );
  };
  return (
    <div className="details-stack">
      <ErrorBanner error={error ?? unlink.error ?? update.error} />
      {section("DESCRIBED_BY", t("A specific version of each document"))}
      {section("REFERENCES", t("Always the latest version"))}
      {editable && <LinkForm obj={obj} onLinked={refresh} />}
    </div>
  );
}

function LinkForm({ obj, onLinked }: { obj: VersionedObject; onLinked: () => void }) {
  const [kind, setKind] = useState<Kind>("DESCRIBED_BY");
  const [text, setText] = useState("");
  const { data: types } = useTypes();
  const search = useObjectSearch(text, 8);
  const isDocument = (o: VersionedObject) => types?.find((ty) => ty.id === o.typeId)?.baseClass === "DOCUMENT";
  const matches = (search.data?.pages.flat() ?? []).filter(isDocument);
  const link = useMutation({
    mutationFn: (documentId: string) =>
      unwrap(api.POST("/api/v1/objects/{id}/document-links", { params: { path: { id: obj.id } }, body: { kind, documentId } })),
    onSuccess: () => {
      setText("");
      onLinked();
    },
  });
  return (
    <div className="panel">
      <div className="add-line" aria-label={t("Link a document")} role="form">
        <select aria-label={t("Link as")} value={kind} onChange={(e) => setKind(e.target.value as Kind)}>
          <option value="DESCRIBED_BY">{t("Described by")}</option>
          <option value="REFERENCES">{t("References")}</option>
        </select>
        <div className="add-line-pick">
          <input
            aria-label={t("Document to link")}
            placeholder={t("Find a document by number or name")}
            value={text}
            onChange={(e) => setText(e.target.value)}
          />
          {text.trim() && (
            <ul className="add-line-results" role="listbox" aria-label={t("Matching documents")}>
              {matches.length === 0 && !search.isFetching && <li className="muted">{t("No document matches")}</li>}
              {matches.map((o) => (
                <li key={o.id} role="option" aria-selected={false} onClick={() => link.mutate(o.id)}>
                  <TypeIcon typeId={o.typeId} /> <strong>{o.number}</strong> {o.name} <span className="muted">{o.version}</span>
                </li>
              ))}
            </ul>
          )}
        </div>
        {link.error != null && (
          <div style={{ flexBasis: "100%" }}>
            <ErrorBanner error={link.error} />
          </div>
        )}
      </div>
    </div>
  );
}

/** A document's Parts tab: the parts whose latest version links to it. */
export function LinkingPartsTab({ obj }: { obj: VersionedObject }) {
  const { data, error, isLoading } = useQuery({
    queryKey: ["linkingParts", obj.id],
    queryFn: () => unwrap(api.GET("/api/v1/objects/{id}/linking-parts", { params: { path: { id: obj.id } } })),
  });
  if (isLoading) return <Loading />;
  return (
    <Panel title={t("Parts")} note={t("Parts whose latest version links to this document")} flush>
      <ErrorBanner error={error} />
      <table className="grid" aria-label={t("Parts")}>
        <thead>
          <tr>
            <th className="icon-cell" />
            <th>{t("Number")}</th>
            <th>{t("Name")}</th>
            <th>{t("Version")}</th>
            <th>{t("State")}</th>
            <th>{t("Link")}</th>
          </tr>
        </thead>
        <tbody>
          {data?.map((l) => (
            <tr key={`${l.part.id}:${l.kind}`}>
              <td className="icon-cell">{l.part.typeId && <TypeIcon typeId={l.part.typeId} />}</td>
              <td className="nowrap">
                <Link className="num-link" to={`/objects/${l.part.id}?tab=documents`}>
                  {l.part.number}
                </Link>
              </td>
              <td>{l.part.name}</td>
              <td className="version">{l.part.version}</td>
              <td>{l.part.state && <StateBadge state={l.part.state} />}</td>
              <td>
                {kindLabel(l.kind)}
                {l.documentVersion && <span className="muted"> · {t("version {version}", { version: l.documentVersion })}</span>}
              </td>
            </tr>
          ))}
          {data?.length === 0 && (
            <tr>
              <td colSpan={6} className="empty">
                {t("No part links to this document.")}
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </Panel>
  );
}

/** Admin: which document types each part type may link to (none: anything). */
export function DocumentLinkRulesAdmin() {
  const qc = useQueryClient();
  const { data: rules, error } = useQuery({
    queryKey: ["documentLinkRules"],
    queryFn: () => unwrap(api.GET("/api/v1/document-link-rules")),
  });
  const { data: types } = useTypes();
  const parts = (types ?? []).filter((ty) => ty.baseClass === "PART");
  const documents = (types ?? []).filter((ty) => ty.baseClass === "DOCUMENT");
  const [partType, setPartType] = useState("");
  const [documentType, setDocumentType] = useState("");
  const [kind, setKind] = useState<Kind>("DESCRIBED_BY");
  const refresh = () => void qc.invalidateQueries({ queryKey: ["documentLinkRules"] });
  const add = useMutation({
    mutationFn: () =>
      unwrap(api.POST("/api/v1/document-link-rules", { body: { partTypeId: partType, documentTypeId: documentType, kind } })),
    onSuccess: refresh,
  });
  const remove = useMutation({
    mutationFn: (ruleId: string) => unwrap(api.DELETE("/api/v1/document-link-rules/{ruleId}", { params: { path: { ruleId } } })),
    onSuccess: refresh,
  });
  const name = (id: string) => typeLabel(types?.find((ty) => ty.id === id));
  return (
    <Panel title={t("Part–document links")} note={t("A part type without rules (here or on its parents) may link to any document")} flush>
      <ErrorBanner error={error ?? add.error ?? remove.error} />
      <table className="grid" aria-label={t("Link rules")}>
        <thead>
          <tr>
            <th>{t("Part type")}</th>
            <th>{t("Link")}</th>
            <th>{t("Document type")}</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {rules?.map((r) => (
            <tr key={r.id}>
              <td>{name(r.partTypeId)}</td>
              <td>{kindLabel(r.kind)}</td>
              <td>{name(r.documentTypeId)}</td>
              <td style={{ width: 1 }}>
                <button className="icon-btn" aria-label={t("Remove the rule")} onClick={() => remove.mutate(r.id)}>
                  <X size={13} />
                </button>
              </td>
            </tr>
          ))}
          {rules?.length === 0 && (
            <tr>
              <td colSpan={4} className="empty">
                {t("No rules: any part may link to any document.")}
              </td>
            </tr>
          )}
        </tbody>
      </table>
      <div className="add-line">
        <select aria-label={t("Part type")} value={partType} onChange={(e) => setPartType(e.target.value)}>
          <option value="">{t("Part type…")}</option>
          {parts.map((ty) => (
            <option key={ty.id} value={ty.id}>
              {typeLabel(ty)}
            </option>
          ))}
        </select>
        <select aria-label={t("Link")} value={kind} onChange={(e) => setKind(e.target.value as Kind)}>
          <option value="DESCRIBED_BY">{t("Described by")}</option>
          <option value="REFERENCES">{t("References")}</option>
        </select>
        <select aria-label={t("Document type")} value={documentType} onChange={(e) => setDocumentType(e.target.value)}>
          <option value="">{t("Document type…")}</option>
          {documents.map((ty) => (
            <option key={ty.id} value={ty.id}>
              {typeLabel(ty)}
            </option>
          ))}
        </select>
        <button className="btn primary" disabled={!partType || !documentType || add.isPending} onClick={() => add.mutate()}>
          <Plus size={14} aria-hidden /> {t("Add rule")}
        </button>
      </div>
    </Panel>
  );
}
