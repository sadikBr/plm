import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckCircle2, Copy, Info, Users } from "lucide-react";
import { Link } from "react-router";
import { api, unwrap } from "../api/client";
import { useTeamRoles } from "../api/queries";
import { pickLabel } from "../components/AttributeForm";
import { ErrorBanner, Loading, Panel, When } from "../components/common";
import { t, tn } from "../i18n/i18n";

/** The SCIM base URL identity providers are given. */
const scimUrl = () => `${window.location.origin}/api/v1/scim/v2`;

/** Users and groups the identity provider pushes over SCIM (roadmap 9.1); each group's role. */
export function DirectoryAdmin() {
  const qc = useQueryClient();
  const { data: status } = useQuery({ queryKey: ["directory"], queryFn: () => unwrap(api.GET("/api/v1/directory")) });
  const groups = useQuery({ queryKey: ["directory", "groups"], queryFn: () => unwrap(api.GET("/api/v1/directory/groups")) });
  const users = useQuery({ queryKey: ["directory", "users"], queryFn: () => unwrap(api.GET("/api/v1/directory/users")) });
  const { data: roles } = useTeamRoles();
  const setRole = useMutation({
    mutationFn: ({ id, role }: { id: string; role: string }) =>
      unwrap(api.PUT("/api/v1/directory/groups/{id}/role", { params: { path: { id } }, body: { role } })),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["directory"] }),
  });
  const connected = (status?.users ?? 0) + (status?.groups ?? 0) > 0;
  return (
    <div className="stack">
      <div className={connected ? "banner ok" : "banner info"}>
        {connected ? <CheckCircle2 size={15} aria-hidden /> : <Info size={15} aria-hidden />}{" "}
        {connected ? (
          <>
            {t("The identity provider keeps {users} people ({active} active) and {groups} groups in sync. Last change", {
              users: status!.users,
              active: status!.activeUsers,
              groups: status!.groups,
            })}{" "}
            <When iso={status?.lastChange} />
          </>
        ) : (
          t("Nothing has been pushed yet. Connect your identity provider's SCIM provisioning to the address below.")
        )}
      </div>
      <Panel title={t("Connect the identity provider")}>
        <ol className="steps">
          <li>
            {t("Make a service account that may administer, and an API token for it with the read, write and admin scopes:")}{" "}
            <Link to="/admin?tab=service-accounts">{t("Service accounts")}</Link>
          </li>
          <li>
            {t("In the identity provider (Entra ID, Okta, Keycloak…), turn on SCIM 2.0 provisioning with this tenant URL and the token:")}
            <div className="copy-line">
              <code>{scimUrl()}</code>
              <button
                className="btn icon"
                title={t("Copy")}
                aria-label={t("Copy")}
                onClick={() => navigator.clipboard?.writeText(scimUrl())}
              >
                <Copy size={14} aria-hidden />
              </button>
            </div>
          </li>
          <li>
            {t(
              "Each group becomes a shared team managed by the directory. Attach it to products and libraries on their Team tab; its members hold the role chosen here.",
            )}
          </li>
        </ol>
      </Panel>
      <Panel title={t("Groups")} note={tn("{n} group", "{n} groups", groups.data?.length ?? 0)} flush>
        <ErrorBanner error={groups.error ?? setRole.error} />
        {groups.isLoading ? (
          <Loading />
        ) : !groups.data?.length ? (
          <p className="empty">{t("No groups yet.")}</p>
        ) : (
          <table className="grid" aria-label={t("Groups")}>
            <thead>
              <tr>
                <th>{t("Group")}</th>
                <th>{t("Members")}</th>
                <th>{t("Role")}</th>
                <th>{t("Attached to")}</th>
                <th>{t("Changed")}</th>
              </tr>
            </thead>
            <tbody>
              {groups.data.map((g) => (
                <tr key={g.id}>
                  <td>
                    <Users size={13} aria-hidden className="muted" /> <strong>{g.displayName}</strong>
                    {g.externalId && <div className="muted small">{g.externalId}</div>}
                  </td>
                  <td className="small">
                    {g.members.length === 0
                      ? "—"
                      : g.members
                          .slice(0, 4)
                          .map((m) => m.userName)
                          .join(", ") + (g.members.length > 4 ? ` +${g.members.length - 4}` : "")}
                  </td>
                  <td>
                    <select
                      aria-label={t("Role of {group}", { group: g.displayName })}
                      value={g.role}
                      onChange={(e) => setRole.mutate({ id: g.id, role: e.target.value })}
                    >
                      {(roles ?? [])
                        .filter((r) => r.active)
                        .map((r) => (
                          <option key={r.key} value={r.key}>
                            {pickLabel(r.labels, r.key)}
                          </option>
                        ))}
                    </select>
                  </td>
                  <td className="small">
                    {g.contexts.length ? g.contexts.join(", ") : <span className="muted">{t("Not attached yet")}</span>}
                  </td>
                  <td className="small">
                    <When iso={g.updatedAt} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Panel>
      <Panel title={t("People")} note={tn("{n} person", "{n} people", users.data?.length ?? 0)} flush>
        {users.isLoading ? (
          <Loading />
        ) : !users.data?.length ? (
          <p className="empty">{t("No people yet.")}</p>
        ) : (
          <table className="grid" aria-label={t("People")}>
            <thead>
              <tr>
                <th>{t("User name")}</th>
                <th>{t("Name")}</th>
                <th>{t("Email")}</th>
                <th>{t("Status")}</th>
              </tr>
            </thead>
            <tbody>
              {users.data.map((u) => (
                <tr key={u.id}>
                  <td>{u.userName}</td>
                  <td>{u.displayName ?? ([u.givenName, u.familyName].filter(Boolean).join(" ") || "—")}</td>
                  <td className="small">{u.email ?? "—"}</td>
                  <td>
                    <span className={u.active ? "tag ok" : "tag"}>{u.active ? t("Active") : t("Deactivated")}</span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Panel>
    </div>
  );
}
