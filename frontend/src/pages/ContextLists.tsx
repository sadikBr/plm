import { Fragment, useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router";
import { useCanOpen, useContextTemplates, useCreateContext, type Context, type ContextKind } from "../api/queries";
import { ContextIcon, Dialog, ErrorBanner, PrincipalName, When, kindInText } from "../components/common";
import { t, tn } from "../i18n/i18n";

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
  // With organizations shown, the list is grouped under each one, by name.
  const orgName = (c: Context) => (c.parentId ? (organizations?.get(c.parentId)?.name ?? "") : "");
  const sorted = organizations
    ? [...contexts].sort(
        (a, b) =>
          orgName(a).localeCompare(orgName(b)) || (a.parentId ?? "").localeCompare(b.parentId ?? "") || a.name.localeCompare(b.name),
      )
    : contexts;
  return (
    <div className="grid-wrap">
      <table className="grid" aria-label={label}>
        <thead>
          <tr>
            <th className="icon-cell">
              <span className="sr-only">{t("Icon")}</span>
            </th>
            <th>{t("Name")}</th>
            <th>{t("Created by")}</th>
            <th>{t("Created")}</th>
          </tr>
        </thead>
        <tbody>
          {sorted.map((c, i) => {
            const org = c.parentId ? organizations?.get(c.parentId) : undefined;
            const groupStart = organizations && (i === 0 || sorted[i - 1]!.parentId !== c.parentId);
            return (
              <Fragment key={c.id}>
                {groupStart && (
                  <tr className="group-row">
                    <td colSpan={4}>
                      <ContextIcon kind="ORGANIZATION" />{" "}
                      {org ? canOpen?.(org) ? <Link to={`/contexts/${org.id}`}>{org.name}</Link> : org.name : t("No organization")}{" "}
                      <span className="muted small">{sorted.filter((x) => x.parentId === c.parentId).length}</span>
                    </td>
                  </tr>
                )}
                <tr>
                  <td className="icon-cell">
                    <ContextIcon kind={c.kind} />
                  </td>
                  <td>
                    <Link className="num-link" to={`/contexts/${c.id}`}>
                      {c.name}
                    </Link>
                  </td>
                  <td className="nowrap">
                    <PrincipalName id={c.createdBy} />
                  </td>
                  <td className="nowrap muted">
                    <When iso={c.createdAt} />
                  </td>
                </tr>
              </Fragment>
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
  const template = templates?.find((tpl) => tpl.id === templateId);

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
      title={newContextTitle(kind, parent && parent.kind !== "SITE" ? parent.name : undefined)}
      onClose={onClose}
      footer={
        <>
          <button className="btn" type="button" onClick={onClose}>
            {t("Cancel")}
          </button>
          <button className="btn primary" type="submit" form={formId} disabled={create.isPending || !name.trim() || !parentId}>
            {t("Create")}
          </button>
        </>
      }
    >
      <form id={formId} onSubmit={submit} className="attr-form">
        <ErrorBanner error={create.error} />
        {!parent && (
          <div className="field">
            <label htmlFor="ctx-parent">{t("Organization")}</label>
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
          <label htmlFor="ctx-name">{t("Name")}</label>
          <input id="ctx-name" value={name} onChange={(e) => setName(e.target.value)} required autoFocus />
          <span className="hint">{t("You will become its manager.")}</span>
        </div>
        {!!templates?.length && (
          <div className="field wide">
            <label htmlFor="ctx-template">{t("Template")}</label>
            <select id="ctx-template" value={templateId} onChange={(e) => setTemplateId(e.target.value)}>
              <option value="">{t("None: start empty")}</option>
              {templates.map((tpl) => (
                <option key={tpl.id} value={tpl.id}>
                  {tpl.name}
                </option>
              ))}
            </select>
            {template && (
              <span className="hint">
                {template.description ? `${template.description} · ` : ""}
                {tn("{n} access rule", "{n} access rules", template.content.policies.length)},{" "}
                {tn("{n} member", "{n} members", template.content.members.length)},{" "}
                {tn("{n} shared team", "{n} shared teams", template.content.sharedTeams.length)}
              </span>
            )}
          </div>
        )}
      </form>
    </Dialog>
  );
}

/** The new-context dialog's title: "New product", or "New product in Acme". */
export function newContextTitle(kind: ContextKind, parent?: string): string {
  switch (kind) {
    case "ORGANIZATION":
      return parent ? t("New organization in {parent}", { parent }) : t("New organization");
    case "PRODUCT":
      return parent ? t("New product in {parent}", { parent }) : t("New product");
    case "LIBRARY":
      return parent ? t("New library in {parent}", { parent }) : t("New library");
    case "PROJECT":
      return parent ? t("New project in {parent}", { parent }) : t("New project");
    default:
      return parent ? t("New {kind} in {parent}", { kind: kindInText(kind), parent }) : t("New {kind}", { kind: kindInText(kind) });
  }
}

/** "3 products" */
export function countOf(n: number, kind: ContextKind) {
  switch (kind) {
    case "ORGANIZATION":
      return tn("{n} organization", "{n} organizations", n);
    case "PRODUCT":
      return tn("{n} product", "{n} products", n);
    case "LIBRARY":
      return tn("{n} library", "{n} libraries", n);
    case "PROJECT":
      return tn("{n} project", "{n} projects", n);
    default:
      return tn("{n} site", "{n} sites", n);
  }
}
