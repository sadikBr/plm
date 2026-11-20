import { Ban, Check, Minus, Plus, ShieldCheck, Trash2, UserPlus } from "lucide-react";
import { useMemo, useState, type FormEvent } from "react";
import { Link } from "react-router";
import {
  findPrincipalByUsername,
  useContextById,
  useCreatePolicy,
  useDeletePolicy,
  useEffectiveMembers,
  useExplain,
  useLifecycles,
  useMembers,
  usePolicies,
  usePrincipal,
  useAttachTeam,
  useAttachedTeams,
  useSharedTeams,
  usePutMember,
  useRemoveMember,
  useTeamRoles,
  useTypes,
  type AccessPolicy,
  type Context,
  type Permission,
  type PolicyEffect,
  type TeamRole,
  type TeamRoleDefinition,
} from "../api/queries";
import { pickLabel } from "../components/AttributeForm";
import { ErrorBanner, Loading, Panel, PrincipalName, Props, formatDate, kindLabel, stateLabel } from "../components/common";

// ---------- Shared bits ----------

export const PERMISSIONS: { key: Permission; label: string; help: string }[] = [
  { key: "READ", label: "Read", help: "See objects and their history" },
  { key: "CREATE", label: "Create", help: "Create objects" },
  { key: "MODIFY", label: "Modify", help: "Check out, edit and check in" },
  { key: "REVISE", label: "Revise", help: "Start a new revision" },
  { key: "PROMOTE", label: "Change state", help: "Move objects along their lifecycle" },
  { key: "MANAGE", label: "Manage", help: "Manage the team, access rules and sub-contexts" },
];
const permissionLabel = (p: Permission) => PERMISSIONS.find((x) => x.key === p)?.label ?? p;

/** Role labels and descriptions, by key. Unknown keys fall back to the key itself. */
function useRoleLookup() {
  const { data: roles } = useTeamRoles();
  return useMemo(() => {
    const byKey = new Map((roles ?? []).map((r) => [r.key, r]));
    return {
      roles: roles ?? [],
      label: (key: TeamRole) => pickLabel(byKey.get(key)?.labels, key),
      description: (key: TeamRole) => byKey.get(key)?.description ?? "",
    };
  }, [roles]);
}

function ContextLink({ id, current }: { id: string; current: string }) {
  const { data } = useContextById(id);
  if (id === current) return <span className="muted">This context</span>;
  return data ? <Link to={`/contexts/${id}?tab=access`}>{data.name}</Link> : <span className="muted">…</span>;
}

function RoleSelect({
  id,
  value,
  onChange,
  roles,
  label,
  labelledBy,
}: {
  id?: string;
  value: TeamRole;
  onChange: (role: TeamRole) => void;
  roles: TeamRoleDefinition[];
  label: (key: TeamRole) => string;
  labelledBy?: string;
}) {
  // Inactive roles can't be given to anyone new, but a current value stays selectable.
  const options = roles.filter((r) => r.active || r.key === value);
  return (
    <select id={id} aria-labelledby={labelledBy} value={value} onChange={(e) => onChange(e.target.value)}>
      {!options.some((r) => r.key === value) && <option value={value}>{label(value)}</option>}
      {options.map((r) => (
        <option key={r.key} value={r.key}>
          {label(r.key)}
        </option>
      ))}
    </select>
  );
}

// ---------- Team ----------

export function TeamTab({ context, canManage }: { context: Context; canManage: boolean }) {
  const { data: members, isLoading, error } = useMembers(context.id);
  const { data: effective } = useEffectiveMembers(context.id);
  const put = usePutMember(context.id);
  const remove = useRemoveMember(context.id);
  const roles = useRoleLookup();
  const inherited = (effective ?? []).filter((m) => m.contextId !== context.id);

  return (
    <div className="cols">
      <div className="stack">
        <Panel title="Team" note={`Roles also apply to everything inside this ${kindLabel(context.kind).toLowerCase()}`} flush>
          <ErrorBanner error={error ?? put.error ?? remove.error} />
          {isLoading ? (
            <Loading />
          ) : !members?.length ? (
            <p className="empty">No direct members.</p>
          ) : (
            <div className="grid-wrap">
              <table className="grid" aria-label="Team members">
                <thead>
                  <tr>
                    <th>User</th>
                    <th id="team-role-col">Role</th>
                    <th>Added</th>
                    {canManage && <th aria-label="Actions" />}
                  </tr>
                </thead>
                <tbody>
                  {members.map((m) => (
                    <tr key={m.principalId}>
                      <td id={`member-${m.principalId}`} className="nowrap">
                        <PrincipalName id={m.principalId} />
                      </td>
                      <td style={{ width: 200 }}>
                        {canManage ? (
                          <RoleSelect
                            // Read as e.g. "Bob Builder Role": which member this changes.
                            labelledBy={`member-${m.principalId} team-role-col`}
                            value={m.role}
                            onChange={(role) => put.mutate({ principalId: m.principalId, role })}
                            roles={roles.roles}
                            label={roles.label}
                          />
                        ) : (
                          <span className="tag" title={roles.description(m.role)}>
                            {roles.label(m.role)}
                          </span>
                        )}
                      </td>
                      <td className="nowrap muted">{formatDate(m.addedAt)}</td>
                      {canManage && (
                        <td style={{ width: 80 }}>
                          <button className="btn small danger" onClick={() => remove.mutate(m.principalId)}>
                            Remove
                          </button>
                        </td>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Panel>
        <SharedTeamsPanel contextId={context.id} canManage={canManage} roleLabel={roles.label} />
        {inherited.length > 0 && (
          <Panel title="Inherited from parent contexts" note="Their roles apply here too" flush>
            <div className="grid-wrap">
              <table className="grid" aria-label="Inherited members">
                <thead>
                  <tr>
                    <th>User</th>
                    <th>Role</th>
                    <th>From</th>
                  </tr>
                </thead>
                <tbody>
                  {inherited.map((m) => (
                    <tr key={`${m.contextId}-${m.principalId}`}>
                      <td className="nowrap">
                        <PrincipalName id={m.principalId} />
                      </td>
                      <td>
                        <span className="tag" title={roles.description(m.role)}>
                          {roles.label(m.role)}
                        </span>
                      </td>
                      <td className="nowrap">
                        <ContextLink id={m.contextId} current={context.id} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Panel>
        )}
      </div>
      {canManage ? <AddMemberPanel contextId={context.id} /> : <RolesPanel />}
    </div>
  );
}

function SharedTeamsPanel({
  contextId,
  canManage,
  roleLabel,
}: {
  contextId: string;
  canManage: boolean;
  roleLabel: (key: TeamRole) => string;
}) {
  const { data: attached } = useAttachedTeams(contextId);
  const { data: all } = useSharedTeams();
  const change = useAttachTeam(contextId);
  const available = (all ?? []).filter((t) => !attached?.some((a) => a.id === t.id));
  if (!canManage && !attached?.length) return null;
  return (
    <Panel
      title="Shared teams"
      note="Teams maintained in one place; their members get their roles here and below"
      flush
      actions={
        canManage &&
        available.length > 0 && (
          <select
            aria-label="Attach a shared team"
            value=""
            onChange={(e) => e.target.value && change.mutate({ teamId: e.target.value, attach: true })}
            style={{ width: 200 }}
          >
            <option value="">Attach a shared team…</option>
            {available.map((t) => (
              <option key={t.id} value={t.id}>
                {t.name}
              </option>
            ))}
          </select>
        )
      }
    >
      <ErrorBanner error={change.error} />
      {!attached?.length ? (
        <p className="empty">No shared teams attached.</p>
      ) : (
        <table className="grid" aria-label="Shared teams">
          <thead>
            <tr>
              <th>Team</th>
              <th>Members</th>
              {canManage && <th aria-label="Actions" />}
            </tr>
          </thead>
          <tbody>
            {attached.map((t) => (
              <tr key={t.id}>
                <td className="nowrap">
                  {t.name}
                  {t.description && <div className="muted">{t.description}</div>}
                </td>
                <td>
                  {t.members.length === 0 ? (
                    <span className="muted">Nobody yet</span>
                  ) : (
                    t.members.map((m, i) => (
                      <span key={m.principalId}>
                        {i > 0 && ", "}
                        <PrincipalName id={m.principalId} /> <span className="muted">({roleLabel(m.role)})</span>
                      </span>
                    ))
                  )}
                </td>
                {canManage && (
                  <td style={{ width: 80 }}>
                    <button className="btn small danger" onClick={() => change.mutate({ teamId: t.id, attach: false })}>
                      Detach
                    </button>
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Panel>
  );
}

function RolesPanel() {
  const roles = useRoleLookup();
  return (
    <Panel title="Roles">
      <Props items={roles.roles.filter((r) => r.active).map((r) => [roles.label(r.key), r.description])} />
    </Panel>
  );
}

function AddMemberPanel({ contextId }: { contextId: string }) {
  const put = usePutMember(contextId);
  const roles = useRoleLookup();
  const [username, setUsername] = useState("");
  const [role, setRole] = useState<TeamRole>("MEMBER");
  const [lookupError, setLookupError] = useState<unknown>();

  const submit = async (e: FormEvent) => {
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
    <form className="panel" aria-labelledby="add-member-title" onSubmit={(e) => void submit(e)}>
      <div className="panel-head">
        <h2 id="add-member-title">Add a member</h2>
      </div>
      <div className="panel-body" style={{ display: "grid", gap: 10 }}>
        <ErrorBanner error={lookupError ?? put.error} />
        <div className="field">
          <label htmlFor="member-username">Username</label>
          <input id="member-username" value={username} onChange={(e) => setUsername(e.target.value)} required />
          <span className="hint">The user must have signed in at least once.</span>
        </div>
        <div className="field">
          <label htmlFor="member-role">Role</label>
          <RoleSelect id="member-role" value={role} onChange={setRole} roles={roles.roles} label={roles.label} />
          {roles.description(role) && <span className="hint">{roles.description(role)}</span>}
        </div>
      </div>
      <div className="panel-foot">
        <button className="btn primary" type="submit" disabled={put.isPending || !username.trim()}>
          <UserPlus size={14} aria-hidden />
          Add
        </button>
      </div>
    </form>
  );
}

// ---------- Access ----------

/** Types and lifecycle states, with labels, for policy targets. */
function useTargets() {
  const { data: types } = useTypes();
  const { data: lifecycles } = useLifecycles();
  return useMemo(() => {
    const states = new Map<string, string>();
    for (const lc of lifecycles ?? []) for (const s of lc.states) if (!states.has(s.key)) states.set(s.key, pickLabel(s.labels, s.key));
    return {
      types: types ?? [],
      states: [...states.entries()].map(([key, label]) => ({ key, label })),
      typeName: (id: string | null | undefined) => (id ? (types?.find((t) => t.id === id)?.displayName ?? "…") : null),
      stateName: (key: string | null | undefined) => (key ? (states.get(key) ?? stateLabel(key)) : null),
    };
  }, [types, lifecycles]);
}

function EffectBadge({ effect }: { effect: PolicyEffect }) {
  return effect === "DENY" ? (
    <span className="tag deny">
      <Ban size={11} aria-hidden /> Deny
    </span>
  ) : (
    <span className="tag allow">
      <Check size={11} aria-hidden /> Allow
    </span>
  );
}

/** "Member · Part and subtypes · Released", for explanations. */
function usePolicySummary() {
  const roles = useRoleLookup();
  const targets = useTargets();
  return (p: AccessPolicy) =>
    [
      roles.label(p.role),
      p.typeId ? `${targets.typeName(p.typeId)} and subtypes` : "All types",
      p.state ? targets.stateName(p.state) : "Any state",
    ].join(" · ");
}

export function AccessTab({ context, canChange }: { context: Context; canChange: boolean }) {
  const { data: policies, isLoading, error } = usePolicies(context.id);
  const remove = useDeletePolicy();
  const roles = useRoleLookup();
  const targets = useTargets();

  return (
    <div className="cols">
      <div className="stack">
        <Panel
          title="Access rules"
          note="A role gets the union of the Allow rules that apply, minus anything a Deny rule takes away"
          flush
        >
          <ErrorBanner error={error ?? remove.error} />
          {isLoading ? (
            <Loading />
          ) : (
            <div className="grid-wrap">
              <table className="grid" aria-label="Access rules">
                <thead>
                  <tr>
                    <th>Effect</th>
                    <th>Role</th>
                    <th>Type</th>
                    <th>State</th>
                    <th>Permissions</th>
                    <th>Defined in</th>
                    {canChange && <th aria-label="Actions" />}
                  </tr>
                </thead>
                <tbody>
                  {(policies ?? []).map((p) => (
                    <tr key={p.id} className={p.inherited ? "inherited" : undefined}>
                      <td>
                        <EffectBadge effect={p.effect} />
                      </td>
                      <td className="nowrap">{roles.label(p.role)}</td>
                      <td className="nowrap">
                        {p.typeId ? (
                          <>
                            {targets.typeName(p.typeId)} <span className="muted">and subtypes</span>
                          </>
                        ) : (
                          <span className="muted">All types</span>
                        )}
                      </td>
                      <td className="nowrap">{p.state ? targets.stateName(p.state) : <span className="muted">Any state</span>}</td>
                      <td>
                        <span className="perm-list">
                          {p.permissions.map((perm) => (
                            <span key={perm} className="perm">
                              {permissionLabel(perm)}
                            </span>
                          ))}
                        </span>
                      </td>
                      <td className="nowrap">
                        <ContextLink id={p.contextId} current={context.id} />
                      </td>
                      {canChange && (
                        <td style={{ width: 40 }}>
                          {!p.inherited && (
                            <button
                              className="btn small ghost icon"
                              aria-label={`Delete rule: ${p.effect === "DENY" ? "deny" : "allow"} ${roles.label(p.role)}`}
                              title="Delete this rule"
                              onClick={() => remove.mutate(p.id)}
                            >
                              <Trash2 size={13} aria-hidden />
                            </button>
                          )}
                        </td>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Panel>
        <CheckAccessPanel context={context} />
      </div>
      {canChange ? (
        <AddRulePanel contextId={context.id} />
      ) : (
        <Panel title="Add a rule">
          <p className="muted">Rules on the site apply everywhere, so only site administrators change them.</p>
        </Panel>
      )}
    </div>
  );
}

function AddRulePanel({ contextId }: { contextId: string }) {
  const create = useCreatePolicy(contextId);
  const roles = useRoleLookup();
  const targets = useTargets();
  const [role, setRole] = useState<TeamRole>("MEMBER");
  const [typeId, setTypeId] = useState("");
  const [state, setState] = useState("");
  const [effect, setEffect] = useState<PolicyEffect>("ALLOW");
  const [permissions, setPermissions] = useState<Set<Permission>>(new Set(["READ"]));

  const submit = (e: FormEvent) => {
    e.preventDefault();
    create.mutate(
      { role, typeId: typeId || null, state: state || null, effect, permissions: PERMISSIONS.map((p) => p.key).filter((p) => permissions.has(p)) },
      { onSuccess: () => setPermissions(new Set(["READ"])) },
    );
  };

  return (
    <form className="panel" aria-labelledby="add-rule-title" onSubmit={submit}>
      <div className="panel-head">
        <ShieldCheck size={14} className="icon-muted" aria-hidden />
        <h2 id="add-rule-title">Add a rule</h2>
      </div>
      <div className="panel-body" style={{ display: "grid", gap: 10 }}>
        <ErrorBanner error={create.error} />
        <div className="segmented" role="radiogroup" aria-label="Effect">
          {(["ALLOW", "DENY"] as const).map((x) => (
            <label key={x} className={effect === x ? "on" : undefined}>
              <input type="radio" name="effect" value={x} checked={effect === x} onChange={() => setEffect(x)} />
              {x === "ALLOW" ? "Allow" : "Deny"}
            </label>
          ))}
        </div>
        <div className="field">
          <label htmlFor="rule-role">Role</label>
          <RoleSelect id="rule-role" value={role} onChange={setRole} roles={roles.roles} label={roles.label} />
        </div>
        <div className="field">
          <label htmlFor="rule-type">Type</label>
          <select id="rule-type" value={typeId} onChange={(e) => setTypeId(e.target.value)}>
            <option value="">All types</option>
            {targets.types.map((t) => (
              <option key={t.id} value={t.id}>
                {t.displayName}
              </option>
            ))}
          </select>
          <span className="hint">A rule on a type also applies to its subtypes.</span>
        </div>
        <div className="field">
          <label htmlFor="rule-state">Lifecycle state</label>
          <select id="rule-state" value={state} onChange={(e) => setState(e.target.value)}>
            <option value="">Any state</option>
            {targets.states.map((s) => (
              <option key={s.key} value={s.key}>
                {s.label}
              </option>
            ))}
          </select>
        </div>
        <fieldset className="attr-group">
          <legend className="section-label">Permissions</legend>
          <div className="checks column">
            {PERMISSIONS.map((p) => (
              <label key={p.key} className="check" title={p.help}>
                <input
                  type="checkbox"
                  checked={permissions.has(p.key)}
                  onChange={(e) => {
                    const next = new Set(permissions);
                    if (e.target.checked) next.add(p.key);
                    else next.delete(p.key);
                    setPermissions(next);
                  }}
                />
                {p.label} <span className="muted">— {p.help.toLowerCase()}</span>
              </label>
            ))}
          </div>
        </fieldset>
      </div>
      <div className="panel-foot">
        <button className="btn primary" type="submit" disabled={create.isPending || permissions.size === 0}>
          <Plus size={14} aria-hidden />
          Add rule
        </button>
      </div>
    </form>
  );
}

function PrincipalOption({ id }: { id: string }) {
  const { data } = usePrincipal(id);
  return <option value={id}>{data?.displayName ?? id}</option>;
}

function CheckAccessPanel({ context }: { context: Context }) {
  const { data: members } = useEffectiveMembers(context.id);
  const targets = useTargets();
  const roles = useRoleLookup();
  const summary = usePolicySummary();
  const people = [...new Set((members ?? []).map((m) => m.principalId))];
  const [principalId, setPrincipalId] = useState("");
  const [typeId, setTypeId] = useState("");
  const [state, setState] = useState("");
  const chosen = principalId || people[0] || "";
  const { data: why, error, isFetching } = useExplain(context.id, chosen, typeId, state);

  return (
    <Panel title="Check access" note="Who can do what here, and which rules decide it">
      <div className="row wrap" style={{ marginBottom: 10 }}>
        <div className="field">
          <label htmlFor="check-person">Person</label>
          <select id="check-person" value={chosen} onChange={(e) => setPrincipalId(e.target.value)}>
            {people.map((p) => (
              <PrincipalOption key={p} id={p} />
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="check-type">Type</label>
          <select id="check-type" value={typeId} onChange={(e) => setTypeId(e.target.value)}>
            <option value="">Any (context-level)</option>
            {targets.types.map((t) => (
              <option key={t.id} value={t.id}>
                {t.displayName}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="check-state">State</label>
          <select id="check-state" value={state} onChange={(e) => setState(e.target.value)}>
            <option value="">Any</option>
            {targets.states.map((s) => (
              <option key={s.key} value={s.key}>
                {s.label}
              </option>
            ))}
          </select>
        </div>
      </div>
      <ErrorBanner error={error} />
      {why && (
        <div aria-busy={isFetching}>
          <p className="muted" style={{ margin: "0 0 8px" }}>
            <PrincipalName id={why.principalId} />{" "}
            {why.roles.length === 0
              ? "has no role here."
              : `is ${why.roles.map((r) => roles.label(r.role)).join(", ")} here.`}
            {why.siteAdmin && " As a site administrator, rules don't limit you."}
          </p>
          <table className="grid" aria-label="Access check">
            <thead>
              <tr>
                <th>Permission</th>
                <th>Result</th>
                <th>Why</th>
              </tr>
            </thead>
            <tbody>
              {why.permissions.map((p) => (
                <tr key={p.permission}>
                  <td className="nowrap">{permissionLabel(p.permission)}</td>
                  <td className="nowrap">
                    {p.granted ? (
                      <span className="result yes">
                        <Check size={12} aria-hidden /> Allowed
                      </span>
                    ) : p.deniedBy.length > 0 ? (
                      <span className="result no">
                        <Ban size={12} aria-hidden /> Denied
                      </span>
                    ) : (
                      <span className="result none">
                        <Minus size={12} aria-hidden /> Not granted
                      </span>
                    )}
                  </td>
                  <td className="muted">
                    {p.deniedBy.length > 0
                      ? `Denied by: ${p.deniedBy.map(summary).join("; ")}`
                      : p.allowedBy.length > 0
                        ? `Allowed by: ${p.allowedBy.map(summary).join("; ")}`
                        : why.siteAdmin
                          ? "Site administrator"
                          : "No rule grants it"}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Panel>
  );
}
