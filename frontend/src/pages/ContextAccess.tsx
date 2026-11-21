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
  useUsableTeams,
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
import { language, t } from "../i18n/i18n";

// ---------- Shared bits ----------

export const PERMISSIONS = (): { key: Permission; label: string; help: string }[] => [
  { key: "READ", label: t("Read"), help: t("See objects and their history") },
  { key: "CREATE", label: t("Create"), help: t("Create objects") },
  { key: "MODIFY", label: t("Modify"), help: t("Check out, edit and check in") },
  { key: "REVISE", label: t("Revise"), help: t("Start a new revision") },
  { key: "PROMOTE", label: t("Change state"), help: t("Move objects along their lifecycle") },
  { key: "MANAGE", label: t("Manage"), help: t("Manage the team, access rules and sub-contexts") },
];
const permissionLabel = (p: Permission) => PERMISSIONS().find((x) => x.key === p)?.label ?? p;

/** Lower case for use inside a sentence; German nouns keep their capital. */
const inSentence = (s: string) => (language() === "de" ? s : s.toLowerCase());

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
  if (id === current) return <span className="muted">{t("This context")}</span>;
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
        <Panel
          title={t("Team")}
          note={t("Roles also apply to everything inside this {kind}", { kind: inSentence(kindLabel(context.kind)) })}
          flush
        >
          <ErrorBanner error={error ?? put.error ?? remove.error} />
          {isLoading ? (
            <Loading />
          ) : !members?.length ? (
            <p className="empty">{t("No direct members.")}</p>
          ) : (
            <div className="grid-wrap">
              <table className="grid" aria-label={t("Team members")}>
                <thead>
                  <tr>
                    <th>{t("User")}</th>
                    <th id="team-role-col">{t("Role")}</th>
                    <th>{t("Added")}</th>
                    {canManage && <th aria-label={t("Actions")} />}
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
                            {t("Remove")}
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
          <Panel title={t("Inherited from parent contexts")} note={t("Their roles apply here too")} flush>
            <div className="grid-wrap">
              <table className="grid" aria-label={t("Inherited members")}>
                <thead>
                  <tr>
                    <th>{t("User")}</th>
                    <th>{t("Role")}</th>
                    <th>{t("From")}</th>
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
  const { data: all } = useUsableTeams(contextId);
  const change = useAttachTeam(contextId);
  const available = (all ?? []).filter((team) => !attached?.some((a) => a.id === team.id));
  if (!canManage && !attached?.length) return null;
  return (
    <Panel
      title={t("Shared teams")}
      note={t("Teams maintained in one place; their members get their roles here and below")}
      flush
      actions={
        canManage &&
        available.length > 0 && (
          <select
            aria-label={t("Attach a shared team")}
            value=""
            onChange={(e) => e.target.value && change.mutate({ teamId: e.target.value, attach: true })}
            style={{ width: 200 }}
          >
            <option value="">{t("Attach a shared team…")}</option>
            {available.map((team) => (
              <option key={team.id} value={team.id}>
                {team.name}
              </option>
            ))}
          </select>
        )
      }
    >
      <ErrorBanner error={change.error} />
      {!attached?.length ? (
        <p className="empty">{t("No shared teams attached.")}</p>
      ) : (
        <table className="grid" aria-label={t("Shared teams")}>
          <thead>
            <tr>
              <th>{t("Team")}</th>
              <th>{t("Members")}</th>
              {canManage && <th aria-label={t("Actions")} />}
            </tr>
          </thead>
          <tbody>
            {attached.map((team) => (
              <tr key={team.id}>
                <td className="nowrap">
                  {team.name}
                  {team.description && <div className="muted">{team.description}</div>}
                </td>
                <td>
                  {team.members.length === 0 ? (
                    <span className="muted">{t("Nobody yet")}</span>
                  ) : (
                    team.members.map((m, i) => (
                      <span key={m.principalId}>
                        {i > 0 && ", "}
                        <PrincipalName id={m.principalId} /> <span className="muted">({roleLabel(m.role)})</span>
                      </span>
                    ))
                  )}
                </td>
                {canManage && (
                  <td style={{ width: 80 }}>
                    <button className="btn small danger" onClick={() => change.mutate({ teamId: team.id, attach: false })}>
                      {t("Detach")}
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
    <Panel title={t("Roles")}>
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
        <h2 id="add-member-title">{t("Add a member")}</h2>
      </div>
      <div className="panel-body" style={{ display: "grid", gap: 10 }}>
        <ErrorBanner error={lookupError ?? put.error} />
        <div className="field">
          <label htmlFor="member-username">{t("Username")}</label>
          <input id="member-username" value={username} onChange={(e) => setUsername(e.target.value)} required />
          <span className="hint">{t("The user must have signed in at least once.")}</span>
        </div>
        <div className="field">
          <label htmlFor="member-role">{t("Role")}</label>
          <RoleSelect id="member-role" value={role} onChange={setRole} roles={roles.roles} label={roles.label} />
          {roles.description(role) && <span className="hint">{roles.description(role)}</span>}
        </div>
      </div>
      <div className="panel-foot">
        <button className="btn primary" type="submit" disabled={put.isPending || !username.trim()}>
          <UserPlus size={14} aria-hidden />
          {t("Add")}
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
      typeName: (id: string | null | undefined) => (id ? (types?.find((x) => x.id === id)?.displayName ?? "…") : null),
      stateName: (key: string | null | undefined) => (key ? (states.get(key) ?? stateLabel(key)) : null),
    };
  }, [types, lifecycles]);
}

function EffectBadge({ effect }: { effect: PolicyEffect }) {
  return effect === "DENY" ? (
    <span className="tag deny">
      <Ban size={11} aria-hidden /> {t("Deny")}
    </span>
  ) : (
    <span className="tag allow">
      <Check size={11} aria-hidden /> {t("Allow")}
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
      p.typeId ? t("{type} and subtypes", { type: targets.typeName(p.typeId) ?? "" }) : t("All types"),
      p.state ? targets.stateName(p.state) : t("Any state"),
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
          title={t("Access rules")}
          note={t("A role gets the union of the Allow rules that apply, minus anything a Deny rule takes away")}
          flush
        >
          <ErrorBanner error={error ?? remove.error} />
          {isLoading ? (
            <Loading />
          ) : (
            <div className="grid-wrap">
              <table className="grid" aria-label={t("Access rules")}>
                <thead>
                  <tr>
                    <th>{t("Effect")}</th>
                    <th>{t("Role")}</th>
                    <th>{t("Type")}</th>
                    <th>{t("State")}</th>
                    <th>{t("Permissions")}</th>
                    <th>{t("Defined in")}</th>
                    {canChange && <th aria-label={t("Actions")} />}
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
                            {targets.typeName(p.typeId)} <span className="muted">{t("and subtypes")}</span>
                          </>
                        ) : (
                          <span className="muted">{t("All types")}</span>
                        )}
                      </td>
                      <td className="nowrap">{p.state ? targets.stateName(p.state) : <span className="muted">{t("Any state")}</span>}</td>
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
                              aria-label={
                                p.effect === "DENY"
                                  ? t("Delete rule: deny {role}", { role: roles.label(p.role) })
                                  : t("Delete rule: allow {role}", { role: roles.label(p.role) })
                              }
                              title={t("Delete this rule")}
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
        <Panel title={t("Add a rule")}>
          <p className="muted">{t("Rules on the site apply everywhere, so only site administrators change them.")}</p>
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
      {
        role,
        typeId: typeId || null,
        state: state || null,
        effect,
        permissions: PERMISSIONS()
          .map((p) => p.key)
          .filter((p) => permissions.has(p)),
      },
      { onSuccess: () => setPermissions(new Set(["READ"])) },
    );
  };

  return (
    <form className="panel" aria-labelledby="add-rule-title" onSubmit={submit}>
      <div className="panel-head">
        <ShieldCheck size={14} className="icon-muted" aria-hidden />
        <h2 id="add-rule-title">{t("Add a rule")}</h2>
      </div>
      <div className="panel-body" style={{ display: "grid", gap: 10 }}>
        <ErrorBanner error={create.error} />
        <div className="segmented" role="radiogroup" aria-label={t("Effect")}>
          {(["ALLOW", "DENY"] as const).map((x) => (
            <label key={x} className={effect === x ? "on" : undefined}>
              <input type="radio" name="effect" value={x} checked={effect === x} onChange={() => setEffect(x)} />
              {x === "ALLOW" ? t("Allow") : t("Deny")}
            </label>
          ))}
        </div>
        <div className="field">
          <label htmlFor="rule-role">{t("Role")}</label>
          <RoleSelect id="rule-role" value={role} onChange={setRole} roles={roles.roles} label={roles.label} />
        </div>
        <div className="field">
          <label htmlFor="rule-type">{t("Type")}</label>
          <select id="rule-type" value={typeId} onChange={(e) => setTypeId(e.target.value)}>
            <option value="">{t("All types")}</option>
            {targets.types.map((x) => (
              <option key={x.id} value={x.id}>
                {x.displayName}
              </option>
            ))}
          </select>
          <span className="hint">{t("A rule on a type also applies to its subtypes.")}</span>
        </div>
        <div className="field">
          <label htmlFor="rule-state">{t("Lifecycle state")}</label>
          <select id="rule-state" value={state} onChange={(e) => setState(e.target.value)}>
            <option value="">{t("Any state")}</option>
            {targets.states.map((s) => (
              <option key={s.key} value={s.key}>
                {s.label}
              </option>
            ))}
          </select>
        </div>
        <fieldset className="attr-group">
          <legend className="section-label">{t("Permissions")}</legend>
          <div className="checks column">
            {PERMISSIONS().map((p) => (
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
                {p.label} <span className="muted">— {inSentence(p.help)}</span>
              </label>
            ))}
          </div>
        </fieldset>
      </div>
      <div className="panel-foot">
        <button className="btn primary" type="submit" disabled={create.isPending || permissions.size === 0}>
          <Plus size={14} aria-hidden />
          {t("Add rule")}
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
    <Panel title={t("Check access")} note={t("Who can do what here, and which rules decide it")}>
      <div className="row wrap" style={{ marginBottom: 10 }}>
        <div className="field">
          <label htmlFor="check-person">{t("Person")}</label>
          <select id="check-person" value={chosen} onChange={(e) => setPrincipalId(e.target.value)}>
            {people.map((p) => (
              <PrincipalOption key={p} id={p} />
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="check-type">{t("Type")}</label>
          <select id="check-type" value={typeId} onChange={(e) => setTypeId(e.target.value)}>
            <option value="">{t("Any (context-level)")}</option>
            {targets.types.map((x) => (
              <option key={x.id} value={x.id}>
                {x.displayName}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="check-state">{t("State")}</label>
          <select id="check-state" value={state} onChange={(e) => setState(e.target.value)}>
            <option value="">{t("Any")}</option>
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
              ? t("has no role here.")
              : t("is {roles} here.", { roles: why.roles.map((r) => roles.label(r.role)).join(", ") })}
            {why.siteAdmin && ` ${t("As a site administrator, rules don't limit you.")}`}
          </p>
          <table className="grid" aria-label={t("Access check")}>
            <thead>
              <tr>
                <th>{t("Permission")}</th>
                <th>{t("Result")}</th>
                <th>{t("Why")}</th>
              </tr>
            </thead>
            <tbody>
              {why.permissions.map((p) => (
                <tr key={p.permission}>
                  <td className="nowrap">{permissionLabel(p.permission)}</td>
                  <td className="nowrap">
                    {p.granted ? (
                      <span className="result yes">
                        <Check size={12} aria-hidden /> {t("Allowed")}
                      </span>
                    ) : p.deniedBy.length > 0 ? (
                      <span className="result no">
                        <Ban size={12} aria-hidden /> {t("Denied")}
                      </span>
                    ) : (
                      <span className="result none">
                        <Minus size={12} aria-hidden /> {t("Not granted")}
                      </span>
                    )}
                  </td>
                  <td className="muted">
                    {p.deniedBy.length > 0
                      ? t("Denied by: {rules}", { rules: p.deniedBy.map(summary).join("; ") })
                      : p.allowedBy.length > 0
                        ? t("Allowed by: {rules}", { rules: p.allowedBy.map(summary).join("; ") })
                        : why.siteAdmin
                          ? t("Site administrator")
                          : t("No rule grants it")}
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
