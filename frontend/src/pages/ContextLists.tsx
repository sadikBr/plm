import { useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router";
import { useCanOpen, useContextTemplates, useCreateContext, type Context, type ContextKind } from "../api/queries";
import { ContextIcon, Dialog, ErrorBanner, PrincipalName, formatDate, kindLabel, kindPlural } from "../components/common";

/** A table of contexts: an organization's products, the site's organizations, every visible library... */
export function ContextTable({
  contexts,
  label,
  organizations,
}: {
  contexts: Context[];
  label: string;
  /** When given, an Organization column names each context's parent. */
  organizations?: Map<string, Context>;
}) {
  const canOpen = useCanOpen();
  return (
    <div className="grid-wrap">
      <table className="grid" aria-label={label}>
        <thead>
          <tr>
            <th className="icon-cell">
              <span className="sr-only">Icon</span>
            </th>
            <th>Name</th>
            {organizations && <th>Organization</th>}
            <th>Created by</th>
            <th>Created</th>
          </tr>
        </thead>
        <tbody>
          {contexts.map((c) => {
            const org = c.parentId ? organizations?.get(c.parentId) : undefined;
            return (
              <tr key={c.id}>
                <td className="icon-cell">
                  <ContextIcon kind={c.kind} />
                </td>
                <td>
                  <Link className="num-link" to={`/contexts/${c.id}`}>
                    {c.name}
                  </Link>
                </td>
                {organizations && (
                  <td className="nowrap">
                    {org ? (
                      canOpen?.(org) ? (
                        <Link to={`/contexts/${org.id}`}>{org.name}</Link>
                      ) : (
                        org.name
                      )
                    ) : (
                      <span className="muted">—</span>
                    )}
                  </td>
                )}
                <td className="nowrap">
                  <PrincipalName id={c.createdBy} />
                </td>
                <td className="nowrap muted">{formatDate(c.createdAt)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

/**
 * Creates a context of [kind]: inside [parent], or inside one of [parents] the user picks (the
 * Products list offers the organizations the user manages).
 */
export function NewContextDialog({
  kind,
  parent,
  parents,
  onClose,
}: {
  kind: ContextKind;
  parent?: Context;
  parents?: Context[];
  onClose: () => void;
}) {
  const create = useCreateContext();
  const navigate = useNavigate();
  const [parentId, setParentId] = useState(parent?.id ?? parents?.[0]?.id ?? "");
  const [name, setName] = useState("");
  const [templateId, setTemplateId] = useState("");
  const formId = "new-context-form";
  const { data: templates } = useContextTemplates(kind, parentId || undefined);
  const template = templates?.find((t) => t.id === templateId);
  const label = kindLabel(kind).toLowerCase();

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!parentId) return;
    create.mutate(
      { parentId, kind, name, templateId: template?.id ?? null },
      {
        onSuccess: (ctx) => {
          onClose();
          navigate(`/contexts/${ctx.id}`);
        },
      },
    );
  };

  return (
    <Dialog
      open
      title={parent && parent.kind !== "SITE" ? `New ${label} in ${parent.name}` : `New ${label}`}
      onClose={onClose}
      footer={
        <>
          <button className="btn" type="button" onClick={onClose}>
            Cancel
          </button>
          <button className="btn primary" type="submit" form={formId} disabled={create.isPending || !name.trim() || !parentId}>
            Create
          </button>
        </>
      }
    >
      <form id={formId} onSubmit={submit} className="attr-form">
        <ErrorBanner error={create.error} />
        {!parent && (
          <div className="field">
            <label htmlFor="ctx-parent">Organization</label>
            <select
              id="ctx-parent"
              value={parentId}
              onChange={(e) => {
                setParentId(e.target.value);
                setTemplateId("");
              }}
            >
              {parents?.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
          </div>
        )}
        <div className="field">
          <label htmlFor="ctx-name">Name</label>
          <input id="ctx-name" value={name} onChange={(e) => setName(e.target.value)} required autoFocus />
          <span className="hint">You will become its manager.</span>
        </div>
        {!!templates?.length && (
          <div className="field wide">
            <label htmlFor="ctx-template">Template</label>
            <select id="ctx-template" value={templateId} onChange={(e) => setTemplateId(e.target.value)}>
              <option value="">None: start empty</option>
              {templates.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.name}
                </option>
              ))}
            </select>
            {template && (
              <span className="hint">
                {template.description ? `${template.description} · ` : ""}
                {template.content.policies.length} access rule
                {template.content.policies.length === 1 ? "" : "s"}, {template.content.members.length} member
                {template.content.members.length === 1 ? "" : "s"}, {template.content.sharedTeams.length} shared team
                {template.content.sharedTeams.length === 1 ? "" : "s"}
              </span>
            )}
          </div>
        )}
      </form>
    </Dialog>
  );
}

/** "3 products" */
export function countOf(n: number, kind: ContextKind) {
  return `${n} ${n === 1 ? kindLabel(kind).toLowerCase() : kindPlural(kind).toLowerCase()}`;
}
