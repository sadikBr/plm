import { Plus, Trash2, UserPlus } from "lucide-react";
import { useState, type FormEvent } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api, unwrap } from "../api/client";
import { findPrincipalByUsername, useContextById, useContextTemplates, useSharedTeams, useTeamRoles, useTypes, type ContextTemplate } from "../api/queries";
import { pickLabel } from "../components/AttributeForm";
import { ErrorBanner, Loading, Panel, PrincipalName, formatDate, kindLabel } from "../components/common";

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
  return <>{data ? data.name : "an organization"}</>;
}

// ---------- Context templates ----------

/** Templates of one organization (its managers), or every template (site admins, no [organizationId]). */
export function ContextTemplatesAdmin({ organizationId }: { organizationId?: string }) {
  const { data: all, isLoading } = useContextTemplates();
  const templates = organizationId ? all?.filter((t) => t.organizationId === organizationId) : all;
  const [selected, setSelected] = useState<string | null>(null);
  const current = templates?.find((t) => t.id === selected) ?? templates?.[0];

  if (isLoading) return <Loading />;
  return (
    <div className="admin-split">
      <Panel title="Context templates" flush>
        {!templates?.length ? (
          <p className="empty">
            No templates yet. Open a {organizationId ? "product, library or project of this organization" : "product, library, project or organization"} set
            up the way you like and use <strong>Save as template</strong>.
          </p>
        ) : (
          <ul className="type-tree" aria-label="Context templates">
            {templates.map((t) => (
              <li key={t.id}>
                <button className={current?.id === t.id ? "type-row active" : "type-row"} onClick={() => setSelected(t.id)}>
                  {t.name}
                  <span className="muted" style={{ marginLeft: "auto" }}>
                    {kindLabel(t.kind)}
                    {!organizationId && !t.organizationId && " · site-wide"}
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
          {kindLabel(template.kind)} template · {template.organizationId ? <OrgName id={template.organizationId} /> : "site-wide"} · updated{" "}
          {formatDate(template.updatedAt)}
        </>
      }
      actions={
        <button className="btn small danger" onClick={() => remove.mutate()} disabled={remove.isPending}>
          <Trash2 size={13} aria-hidden />
          Delete
        </button>
      }
      footer={
        <button className="btn primary" disabled={!dirty || save.isPending || !name.trim()} onClick={() => save.mutate()}>
          Save
        </button>
      }
    >
      <ErrorBanner error={save.error ?? remove.error} />
      <div className="attr-form cols-2">
        <div className="field">
          <label htmlFor="tpl-name">Name</label>
          <input id="tpl-name" value={name} onChange={(e) => setName(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="tpl-description">Description</label>
          <input id="tpl-description" value={description} onChange={(e) => setDescription(e.target.value)} />
        </div>
      </div>
      <div className="section-label" style={{ marginTop: 14 }}>
        Access rules ({template.content.policies.length})
      </div>
      {template.content.policies.length === 0 ? (
        <p className="muted">None: the new context inherits its parent's rules only.</p>
      ) : (
        <table className="grid" aria-label="Template access rules">
          <thead>
            <tr>
              <th>Effect</th>
              <th>Role</th>
              <th>Type</th>
              <th>State</th>
              <th>Permissions</th>
            </tr>
          </thead>
          <tbody>
            {template.content.policies.map((p, i) => (
              <tr key={i}>
                <td>{p.effect === "DENY" ? "Deny" : "Allow"}</td>
                <td>{roleLabel(p.role)}</td>
                <td>{p.type ? (types?.find((t) => t.name === p.type)?.displayName ?? p.type) : <span className="muted">All types</span>}</td>
                <td>{p.state ?? <span className="muted">Any state</span>}</td>
                <td>{p.permissions.join(", ")}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <div className="section-label" style={{ marginTop: 14 }}>
        Team ({template.content.members.length})
      </div>
      {template.content.members.length === 0 ? (
        <p className="muted">Only the creator, as manager.</p>
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
        Shared teams
      </div>
      <p style={{ margin: "4px 0" }}>{template.content.sharedTeams.join(", ") || <span className="muted">None</span>}</p>
      <p className="muted" style={{ marginBottom: 0 }}>
        To change the rules or team, set up a context the way you want and save it as a template again.
      </p>
    </Panel>
  );
}

// ---------- Shared teams ----------

/** Shared teams of one organization (its managers), or every team (site admins, no [organizationId]). */
export function SharedTeamsAdmin({ organizationId }: { organizationId?: string }) {
  const { data: all, isLoading } = useSharedTeams();
  const teams = organizationId ? all?.filter((t) => t.organizationId === organizationId) : all;
  const invalidate = useInvalidate();
  const [selected, setSelected] = useState<string | null>(null);
  const [name, setName] = useState("");
  const current = teams?.find((t) => t.id === selected) ?? teams?.[0];
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
        title="Shared teams"
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
            <input aria-label="New team name" placeholder="New team, e.g. Quality" value={name} onChange={(e) => setName(e.target.value)} />
            <button className="btn" type="submit" disabled={!name.trim() || create.isPending}>
              <Plus size={14} aria-hidden />
              Create
            </button>
          </form>
        }
      >
        <ErrorBanner error={create.error} />
        <ul className="type-tree" aria-label="Shared teams">
          {teams?.map((t) => (
            <li key={t.id}>
              <button className={current?.id === t.id ? "type-row active" : "type-row"} onClick={() => setSelected(t.id)}>
                {t.name}
                <span className="muted" style={{ marginLeft: "auto" }}>
                  {!organizationId && t.organizationId ? "org · " : ""}
                  {t.members.length}
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
  const team = teams?.find((t) => t.id === teamId);
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
      note="Attach it to contexts from their Team tab; changes here apply everywhere it is attached"
      flush
      actions={
        <button className="btn small danger" onClick={() => removeTeam.mutate()} disabled={removeTeam.isPending}>
          <Trash2 size={13} aria-hidden />
          Delete team
        </button>
      }
      footer={
        <form className="row" style={{ width: "100%" }} onSubmit={(e) => void add(e)} aria-label="Add a team member">
          <input aria-label="Username" placeholder="Username" value={username} onChange={(e) => setUsername(e.target.value)} />
          <select aria-label="Role" value={role} onChange={(e) => setRole(e.target.value)} style={{ width: 180 }}>
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
            Add
          </button>
        </form>
      }
    >
      <ErrorBanner error={lookupError ?? put.error ?? remove.error ?? removeTeam.error} />
      {team.members.length === 0 ? (
        <p className="empty">No members yet.</p>
      ) : (
        <table className="grid" aria-label="Team members">
          <thead>
            <tr>
              <th>User</th>
              <th>Role</th>
              <th>Added</th>
              <th aria-label="Actions" />
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
                    Remove
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
