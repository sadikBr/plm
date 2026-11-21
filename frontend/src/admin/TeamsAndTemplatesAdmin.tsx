import { Plus, Trash2, UserPlus } from "lucide-react";
import { useState, type FormEvent } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api, unwrap } from "../api/client";
import {
  findPrincipalByUsername,
  useContextById,
  useContextTemplates,
  useSharedTeams,
  useTeamRoles,
  useTypes,
  type ContextTemplate,
} from "../api/queries";
import { pickLabel } from "../components/AttributeForm";
import { ErrorBanner, Loading, Panel, PrincipalName, formatDate, kindLabel } from "../components/common";
import { t } from "../i18n/i18n";

function useInvalidate() {
  const qc = useQueryClient();
  return () => {
    for (const key of ["sharedTeams", "attachedTeams", "contextTemplates", "effectiveMembers", "permissions", "explain"]) {
      void qc.invalidateQueries({ queryKey: [key] });
    }
  };
}

function OrgName({ id }: { id: string }) {
  const { data } = useContextById(id);
  return <>{data ? data.name : t("an organization")}</>;
}

// ---------- Context templates ----------

/** Templates of one organization (its managers), or every template (site admins, no [organizationId]). */
export function ContextTemplatesAdmin({ organizationId }: { organizationId?: string }) {
  const { data: all, isLoading } = useContextTemplates();
  const templates = organizationId ? all?.filter((tpl) => tpl.organizationId === organizationId) : all;
  const [selected, setSelected] = useState<string | null>(null);
  const current = templates?.find((tpl) => tpl.id === selected) ?? templates?.[0];

  if (isLoading) return <Loading />;
  return (
    <div className="admin-split">
      <Panel title={t("Context templates")} flush>
        {!templates?.length ? (
          <p className="empty">
            {organizationId
              ? t("No templates yet. Open a product, library or project of this organization set up the way you like and use")
              : t("No templates yet. Open a product, library, project or organization set up the way you like and use")}{" "}
            <strong>{t("Save as template")}</strong>.
          </p>
        ) : (
          <ul className="type-tree" aria-label={t("Context templates")}>
            {templates.map((tpl) => (
              <li key={tpl.id}>
                <button className={current?.id === tpl.id ? "type-row active" : "type-row"} onClick={() => setSelected(tpl.id)}>
                  {tpl.name}
                  <span className="muted" style={{ marginLeft: "auto" }}>
                    {kindLabel(tpl.kind)}
                    {!organizationId && !tpl.organizationId && ` · ${t("site-wide")}`}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </Panel>
      {current && <TemplateDetails key={current.id} template={current} />}
    </div>
  );
}

function TemplateDetails({ template }: { template: ContextTemplate }) {
  const invalidate = useInvalidate();
  const { data: roles } = useTeamRoles();
  const { data: types } = useTypes();
  const [name, setName] = useState(template.name);
  const [description, setDescription] = useState(template.description ?? "");
  const roleLabel = (key: string) => pickLabel(roles?.find((r) => r.key === key)?.labels, key);
  const save = useMutation({
    mutationFn: () =>
      unwrap(
        api.PUT("/api/v1/context-templates/{id}", {
          params: { path: { id: template.id } },
          body: { name: name.trim(), kind: template.kind, description: description.trim() || null, content: template.content },
        }),
      ),
    onSuccess: invalidate,
  });
  const remove = useMutation({
    mutationFn: () => unwrap(api.DELETE("/api/v1/context-templates/{id}", { params: { path: { id: template.id } } })),
    onSuccess: invalidate,
  });
  const dirty = name.trim() !== template.name || (description.trim() || null) !== (template.description ?? null);

  return (
    <Panel
      title={template.name}
      note={
        <>
          {t("{kind} template", { kind: kindLabel(template.kind) })} ·{" "}
          {template.organizationId ? <OrgName id={template.organizationId} /> : t("site-wide")} ·{" "}
          {t("updated {date}", { date: formatDate(template.updatedAt) })}
        </>
      }
      actions={
        <button className="btn small danger" onClick={() => remove.mutate()} disabled={remove.isPending}>
          <Trash2 size={13} aria-hidden />
          {t("Delete")}
        </button>
      }
      footer={
        <button className="btn primary" disabled={!dirty || save.isPending || !name.trim()} onClick={() => save.mutate()}>
          {t("Save")}
        </button>
      }
    >
      <ErrorBanner error={save.error ?? remove.error} />
      <div className="attr-form cols-2">
        <div className="field">
          <label htmlFor="tpl-name">{t("Name")}</label>
          <input id="tpl-name" value={name} onChange={(e) => setName(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="tpl-description">{t("Description")}</label>
          <input id="tpl-description" value={description} onChange={(e) => setDescription(e.target.value)} />
        </div>
      </div>
      <div className="section-label" style={{ marginTop: 14 }}>
        {t("Access rules ({count})", { count: template.content.policies.length })}
      </div>
      {template.content.policies.length === 0 ? (
        <p className="muted">{t("None: the new context inherits its parent's rules only.")}</p>
      ) : (
        <table className="grid" aria-label={t("Template access rules")}>
          <thead>
            <tr>
              <th>{t("Effect")}</th>
              <th>{t("Role")}</th>
              <th>{t("Type")}</th>
              <th>{t("State")}</th>
              <th>{t("Permissions")}</th>
            </tr>
          </thead>
          <tbody>
            {template.content.policies.map((p, i) => (
              <tr key={i}>
                <td>{p.effect === "DENY" ? t("Deny") : t("Allow")}</td>
                <td>{roleLabel(p.role)}</td>
                <td>
                  {p.type ? (
                    (types?.find((ty) => ty.name === p.type)?.displayName ?? p.type)
                  ) : (
                    <span className="muted">{t("All types")}</span>
                  )}
                </td>
                <td>{p.state ?? <span className="muted">{t("Any state")}</span>}</td>
                <td>{p.permissions.join(", ")}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <div className="section-label" style={{ marginTop: 14 }}>
        {t("Team ({count})", { count: template.content.members.length })}
      </div>
      {template.content.members.length === 0 ? (
        <p className="muted">{t("Only the creator, as manager.")}</p>
      ) : (
        <p style={{ margin: "4px 0" }}>
          {template.content.members.map((m, i) => (
            <span key={m.principalId}>
              {i > 0 && ", "}
              <PrincipalName id={m.principalId} /> <span className="muted">({roleLabel(m.role)})</span>
            </span>
          ))}
        </p>
      )}
      <div className="section-label" style={{ marginTop: 14 }}>
        {t("Shared teams")}
      </div>
      <p style={{ margin: "4px 0" }}>{template.content.sharedTeams.join(", ") || <span className="muted">{t("None")}</span>}</p>
      <p className="muted" style={{ marginBottom: 0 }}>
        {t("To change the rules or team, set up a context the way you want and save it as a template again.")}
      </p>
    </Panel>
  );
}

// ---------- Shared teams ----------

/** Shared teams of one organization (its managers), or every team (site admins, no [organizationId]). */
export function SharedTeamsAdmin({ organizationId }: { organizationId?: string }) {
  const { data: all, isLoading } = useSharedTeams();
  const teams = organizationId ? all?.filter((tm) => tm.organizationId === organizationId) : all;
  const invalidate = useInvalidate();
  const [selected, setSelected] = useState<string | null>(null);
  const [name, setName] = useState("");
  const current = teams?.find((tm) => tm.id === selected) ?? teams?.[0];
  const create = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/shared-teams", { body: { name: name.trim(), organizationId: organizationId ?? null } })),
    onSuccess: (team) => {
      setName("");
      setSelected(team.id);
      invalidate();
    },
  });

  if (isLoading) return <Loading />;
  return (
    <div className="admin-split">
      <Panel
        title={t("Shared teams")}
        flush
        footer={
          <form
            className="row"
            style={{ width: "100%" }}
            onSubmit={(e) => {
              e.preventDefault();
              create.mutate();
            }}
          >
            <input
              aria-label={t("New team name")}
              placeholder={t("New team, e.g. Quality")}
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
            <button className="btn" type="submit" disabled={!name.trim() || create.isPending}>
              <Plus size={14} aria-hidden />
              {t("Create")}
            </button>
          </form>
        }
      >
        <ErrorBanner error={create.error} />
        <ul className="type-tree" aria-label={t("Shared teams")}>
          {teams?.map((tm) => (
            <li key={tm.id}>
              <button className={current?.id === tm.id ? "type-row active" : "type-row"} onClick={() => setSelected(tm.id)}>
                {tm.name}
                <span className="muted" style={{ marginLeft: "auto" }}>
                  {!organizationId && tm.organizationId ? `${t("org")} · ` : ""}
                  {tm.members.length}
                </span>
              </button>
            </li>
          ))}
        </ul>
      </Panel>
      {current && <TeamDetails key={current.id} teamId={current.id} />}
    </div>
  );
}

function TeamDetails({ teamId }: { teamId: string }) {
  const { data: teams } = useSharedTeams();
  const { data: roles } = useTeamRoles();
  const invalidate = useInvalidate();
  const team = teams?.find((tm) => tm.id === teamId);
  const [username, setUsername] = useState("");
  const [role, setRole] = useState("MEMBER");
  const [lookupError, setLookupError] = useState<unknown>();
  const put = useMutation({
    mutationFn: ({ principalId, role }: { principalId: string; role: string }) =>
      unwrap(api.PUT("/api/v1/shared-teams/{id}/members/{principalId}", { params: { path: { id: teamId, principalId } }, body: { role } })),
    onSuccess: invalidate,
  });
  const remove = useMutation({
    mutationFn: (principalId: string) =>
      unwrap(api.DELETE("/api/v1/shared-teams/{id}/members/{principalId}", { params: { path: { id: teamId, principalId } } })),
    onSuccess: invalidate,
  });
  const removeTeam = useMutation({
    mutationFn: () => unwrap(api.DELETE("/api/v1/shared-teams/{id}", { params: { path: { id: teamId } } })),
    onSuccess: invalidate,
  });
  if (!team) return null;
  const roleLabel = (key: string) => pickLabel(roles?.find((r) => r.key === key)?.labels, key);

  const add = async (e: FormEvent) => {
    e.preventDefault();
    setLookupError(undefined);
    try {
      const principal = await findPrincipalByUsername(username.trim());
      put.mutate({ principalId: principal.id, role }, { onSuccess: () => setUsername("") });
    } catch (err) {
      setLookupError(err);
    }
  };

  return (
    <Panel
      title={team.name}
      note={t("Attach it to contexts from their Team tab; changes here apply everywhere it is attached")}
      flush
      actions={
        <button className="btn small danger" onClick={() => removeTeam.mutate()} disabled={removeTeam.isPending}>
          <Trash2 size={13} aria-hidden />
          {t("Delete team")}
        </button>
      }
      footer={
        <form className="row" style={{ width: "100%" }} onSubmit={(e) => void add(e)} aria-label={t("Add a team member")}>
          <input aria-label={t("Username")} placeholder={t("Username")} value={username} onChange={(e) => setUsername(e.target.value)} />
          <select aria-label={t("Role")} value={role} onChange={(e) => setRole(e.target.value)} style={{ width: 180 }}>
            {roles
              ?.filter((r) => r.active)
              .map((r) => (
                <option key={r.key} value={r.key}>
                  {roleLabel(r.key)}
                </option>
              ))}
          </select>
          <button className="btn" type="submit" disabled={!username.trim() || put.isPending}>
            <UserPlus size={14} aria-hidden />
            {t("Add")}
          </button>
        </form>
      }
    >
      <ErrorBanner error={lookupError ?? put.error ?? remove.error ?? removeTeam.error} />
      {team.members.length === 0 ? (
        <p className="empty">{t("No members yet.")}</p>
      ) : (
        <table className="grid" aria-label={t("Team members")}>
          <thead>
            <tr>
              <th>{t("User")}</th>
              <th>{t("Role")}</th>
              <th>{t("Added")}</th>
              <th aria-label={t("Actions")} />
            </tr>
          </thead>
          <tbody>
            {team.members.map((m) => (
              <tr key={m.principalId}>
                <td>
                  <PrincipalName id={m.principalId} />
                </td>
                <td>{roleLabel(m.role)}</td>
                <td className="muted">{formatDate(m.addedAt)}</td>
                <td style={{ width: 80 }}>
                  <button className="btn small danger" onClick={() => remove.mutate(m.principalId)}>
                    {t("Remove")}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Panel>
  );
}
