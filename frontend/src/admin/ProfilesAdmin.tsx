import { Pencil, Plus, Trash2, Users, X } from "lucide-react";
import { Fragment, useState } from "react";
import { findPrincipalByUsername, useActionRegistry, useSharedTeams } from "../api/queries";
import { Dialog, ErrorBanner, Loading, Panel, PrincipalName, formatDate } from "../components/common";
import {
  useCreateProfile,
  useDeleteProfile,
  useProfiles,
  useUiAreas,
  useUpdateProfile,
  type Profile,
  type ProfileMember,
} from "./adminQueries";
import { t, tn } from "../i18n/i18n";

/**
 * Profiles (roadmap 2.8): which actions and parts of the interface a group of people sees. They
 * shape the interface for a job (a reviewer, a supplier); access rules still decide what anyone may do.
 */
export function ProfilesAdmin() {
  const { data: profiles, isLoading, error } = useProfiles();
  const { data: areas } = useUiAreas();
  const { data: teams } = useSharedTeams();
  const remove = useDeleteProfile();
  const [editing, setEditing] = useState<Profile | "new" | null>(null);
  const areaLabel = new Map((areas ?? []).map((a) => [a.key, a.label]));
  const teamName = new Map((teams ?? []).map((team) => [team.id, team.name]));

  if (isLoading) return <Loading />;
  return (
    <>
      <Panel
        title={t("Profiles")}
        note={t("People in no profile see everything; in several, they see what any of them shows. Access rules still apply")}
        flush
        actions={
          <button className="btn small primary" onClick={() => setEditing("new")}>
            <Plus size={13} aria-hidden />
            {t("New profile")}
          </button>
        }
      >
        <ErrorBanner error={error ?? remove.error} />
        {!profiles?.length ? (
          <p className="empty">
            {t("No profiles yet. A profile hides actions and screens a group of people doesn't need, such as reviewers or suppliers.")}
          </p>
        ) : (
          <table className="grid" aria-label={t("Profiles")}>
            <thead>
              <tr>
                <th>{t("Profile")}</th>
                <th>{t("Members")}</th>
                <th>{t("Hides")}</th>
                <th>{t("Updated")}</th>
                <th aria-label={t("Actions")} />
              </tr>
            </thead>
            <tbody>
              {profiles.map((p) => (
                <tr key={p.id}>
                  <td>
                    <strong>{p.name}</strong>
                    {p.description && <div className="muted small">{p.description}</div>}
                  </td>
                  <td>
                    <div className="row wrap" style={{ gap: 4 }}>
                      {p.members.length === 0 && <span className="muted">{t("Nobody yet")}</span>}
                      {p.members.map((m) => (
                        <span key={`${m.kind}:${m.id}`} className="tag">
                          {m.kind === "SHARED_TEAM" ? (
                            <>
                              <Users size={11} aria-hidden /> {teamName.get(m.id) ?? t("Shared team")}
                            </>
                          ) : (
                            <PrincipalName id={m.id} />
                          )}
                        </span>
                      ))}
                    </div>
                  </td>
                  <td>
                    <div className="row wrap" style={{ gap: 4 }}>
                      {p.hiddenAreas.map((a) => (
                        <span key={a} className="tag warn">
                          {areaLabel.get(a) ?? a}
                        </span>
                      ))}
                      {p.hiddenActions.length > 0 && (
                        <span className="tag" title={p.hiddenActions.join(", ")}>
                          {tn("{n} action", "{n} actions", p.hiddenActions.length)}
                        </span>
                      )}
                    </div>
                  </td>
                  <td className="nowrap muted">
                    {formatDate(p.updatedAt)} · <PrincipalName id={p.updatedBy} />
                  </td>
                  <td className="nowrap">
                    <button className="icon-btn" aria-label={t("Edit {name}", { name: p.name })} onClick={() => setEditing(p)}>
                      <Pencil size={13} />
                    </button>
                    <button
                      className="icon-btn"
                      aria-label={t("Delete {name}", { name: p.name })}
                      disabled={remove.isPending}
                      onClick={() =>
                        window.confirm(t("Delete the profile “{name}”? Its members see everything again.", { name: p.name })) &&
                        remove.mutate(p.id)
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
      </Panel>
      {editing && <ProfileDialog profile={editing === "new" ? null : editing} onClose={() => setEditing(null)} />}
    </>
  );
}

function ProfileDialog({ profile, onClose }: { profile: Profile | null; onClose: () => void }) {
  const { data: registry } = useActionRegistry();
  const { data: areas } = useUiAreas();
  const { data: teams } = useSharedTeams();
  const create = useCreateProfile();
  const update = useUpdateProfile();
  const [name, setName] = useState(profile?.name ?? "");
  const [description, setDescription] = useState(profile?.description ?? "");
  const [members, setMembers] = useState<ProfileMember[]>(profile?.members ?? []);
  const [hiddenActions, setHiddenActions] = useState<string[]>(profile?.hiddenActions ?? []);
  const [hiddenAreas, setHiddenAreas] = useState<string[]>(profile?.hiddenAreas ?? []);
  const [username, setUsername] = useState("");
  const [lookupError, setLookupError] = useState<unknown>();
  const saving = create.isPending || update.isPending;
  const toggle = (list: string[], set: (v: string[]) => void, v: string) =>
    set(list.includes(v) ? list.filter((x) => x !== v) : [...list, v]);
  const addMember = (m: ProfileMember) => setMembers((ms) => (ms.some((x) => x.kind === m.kind && x.id === m.id) ? ms : [...ms, m]));

  const addUser = async () => {
    setLookupError(undefined);
    try {
      const p = await findPrincipalByUsername(username.trim());
      addMember({ kind: "USER", id: p.id });
      setUsername("");
    } catch (e) {
      setLookupError(e);
    }
  };

  // Actions by where they come from: built in, or an extension.
  const groups = new Map<string, { id: string; label: string }[]>();
  for (const a of registry ?? []) {
    const source = a.source === "platform" || !a.source ? t("Built in") : t("Extension {id}", { id: a.source });
    groups.set(source, [...(groups.get(source) ?? []), { id: a.id, label: a.effective.label }]);
  }

  const save = () => {
    const body = { name, description: description || null, members, hiddenActions, hiddenAreas };
    if (profile) update.mutate({ id: profile.id, body }, { onSuccess: onClose });
    else create.mutate(body, { onSuccess: onClose });
  };

  return (
    <Dialog
      open
      wide
      title={profile ? t("Edit {name}", { name: profile.name }) : t("New profile")}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            {t("Cancel")}
          </button>
          <button className="btn primary" disabled={saving || !name.trim()} onClick={save}>
            {t("Save")}
          </button>
        </>
      }
    >
      <div className="attr-form">
        <ErrorBanner error={create.error ?? update.error ?? lookupError} />
        <div className="field">
          <label htmlFor="prof-name">{t("Name")}</label>
          <input id="prof-name" value={name} onChange={(e) => setName(e.target.value)} placeholder={t("Reviewers")} autoFocus />
        </div>
        <div className="field">
          <label htmlFor="prof-desc">{t("Description")}</label>
          <input id="prof-desc" value={description} onChange={(e) => setDescription(e.target.value)} placeholder={t("Who it's for")} />
        </div>
        <fieldset className="members-box wide">
          <legend>{t("Members")}</legend>
          <div className="row wrap" style={{ gap: 4 }}>
            {members.length === 0 && <span className="muted small">{t("Add people or shared teams.")}</span>}
            {members.map((m) => (
              <span key={`${m.kind}:${m.id}`} className="tag">
                {m.kind === "SHARED_TEAM" ? (
                  <>
                    <Users size={11} aria-hidden /> {teams?.find((team) => team.id === m.id)?.name ?? t("Shared team")}
                  </>
                ) : (
                  <PrincipalName id={m.id} />
                )}
                <button
                  type="button"
                  className="tag-remove"
                  aria-label={t("Remove member")}
                  onClick={() => setMembers(members.filter((x) => x !== m))}
                >
                  <X size={11} />
                </button>
              </span>
            ))}
          </div>
          <div className="member-add">
            <input
              aria-label={t("Username")}
              placeholder={t("Username")}
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter" && username.trim()) {
                  e.preventDefault();
                  void addUser();
                }
              }}
            />
            <button type="button" className="btn small" disabled={!username.trim()} onClick={() => void addUser()}>
              {t("Add user")}
            </button>
            <select
              aria-label={t("Add a shared team")}
              value=""
              onChange={(e) => e.target.value && addMember({ kind: "SHARED_TEAM", id: e.target.value })}
            >
              <option value="">{t("Add a shared team…")}</option>
              {teams?.map((team) => (
                <option key={team.id} value={team.id}>
                  {team.name}
                </option>
              ))}
            </select>
          </div>
        </fieldset>
        <fieldset className="check-list wide">
          <legend>{t("Hide these parts of the interface")}</legend>
          {areas?.map((a) => (
            <label key={a.key} className="check">
              <input type="checkbox" checked={hiddenAreas.includes(a.key)} onChange={() => toggle(hiddenAreas, setHiddenAreas, a.key)} />
              {a.label}
            </label>
          ))}
        </fieldset>
        <fieldset className="check-list tall wide">
          <legend>{t("Hide these actions")}</legend>
          {[...groups.entries()].map(([source, actions]) => (
            <Fragment key={source}>
              <div className="check-list-title">{source}</div>
              {actions.map((a) => (
                <label key={a.id} className="check" title={a.id}>
                  <input
                    type="checkbox"
                    data-action={a.id}
                    checked={hiddenActions.includes(a.id)}
                    onChange={() => toggle(hiddenActions, setHiddenActions, a.id)}
                  />
                  {a.label}
                </label>
              ))}
            </Fragment>
          ))}
          <span className="hint check-list-title">
            {t("Hidden actions aren't offered in toolbars and menus. Profiles don't change what people may do through the API.")}
          </span>
        </fieldset>
      </div>
    </Dialog>
  );
}
