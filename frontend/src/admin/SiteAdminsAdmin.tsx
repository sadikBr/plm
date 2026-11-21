import { Bot, ShieldCheck, User } from "lucide-react";
import { ErrorBanner, Loading, Panel, formatDate } from "../components/common";
import { t, tn } from "../i18n/i18n";
import { useSiteAdministrators, type SiteAdministrators } from "./adminQueries";

/** Shown above every administration tab while the site has more than the one recommended administrator. */
export function SiteAdminsNotice({ onOpen }: { onOpen: () => void }) {
  const { data } = useSiteAdministrators();
  if (!data?.moreThanRecommended) return null;
  return (
    <div className="banner warn site-admins-notice" role="status">
      {t("This site has {n} site administrators; one, the built-in administrator, is recommended.", { n: data.administrators.length })}{" "}
      <button className="link-btn" onClick={onOpen}>
        {t("Review them")}
      </button>
    </div>
  );
}

/** Who administers the site: the built-in administrator, and anyone else given the rights. */
export function SiteAdminsAdmin() {
  const { data, isLoading, error } = useSiteAdministrators();
  if (isLoading) return <Loading />;
  if (error || !data) return <ErrorBanner error={error} />;
  const others = data.administrators.filter((a) => !a.builtIn);
  const builtInSeen = data.administrators.some((a) => a.builtIn);

  return (
    <div className="stack">
      <Panel title={t("Site administrators")}>
        <div className="panel-body stack">
          <p className="muted small">
            {t(
              "The built-in administrator, {name}, is made with the installation and is the one account meant to administer the site. Everyone else works in organizations and products, where their roles there give them what they need, including organization administration.",
              { name: data.builtInUsername },
            )}
          </p>
          {data.enforced ? (
            <div className="banner info">{t("Only the built-in administrator gets site administration rights on this site.")}</div>
          ) : others.length > 0 ? (
            <div className="banner warn" role="status">
              {tn(
                "{n} more account has site administration rights. It can see and change everything: remove the role where it isn't needed.",
                "{n} more accounts have site administration rights. Each one can see and change everything: remove the role where it isn't needed.",
                others.length,
              )}
            </div>
          ) : (
            <div className="banner ok">
              <ShieldCheck size={14} aria-hidden />
              {t("Only the built-in administrator administers this site, as recommended.")}
            </div>
          )}
          <table className="grid" aria-label={t("Site administrators")}>
            <thead>
              <tr>
                <th>{t("Account")}</th>
                <th>{t("Username")}</th>
                <th>{t("Kind")}</th>
                <th>{t("Last signed in")}</th>
              </tr>
            </thead>
            <tbody>
              {!builtInSeen && (
                <tr>
                  <td>
                    <User size={13} aria-hidden /> <strong>{t("Built-in administrator")}</strong>{" "}
                    <span className="tag">{t("built-in")}</span>
                  </td>
                  <td>
                    <code>{data.builtInUsername}</code>
                  </td>
                  <td>{t("User")}</td>
                  <td className="muted">{t("Not signed in yet")}</td>
                </tr>
              )}
              {data.administrators.map((a) => (
                <tr key={a.id}>
                  <td>
                    {a.kind === "SERVICE_ACCOUNT" ? <Bot size={13} aria-hidden /> : <User size={13} aria-hidden />}{" "}
                    <strong>{a.displayName}</strong>{" "}
                    {a.builtIn ? <span className="tag allow">{t("built-in")}</span> : <span className="tag warn">{t("additional")}</span>}
                  </td>
                  <td>
                    <code>{a.username}</code>
                  </td>
                  <td>{a.kind === "SERVICE_ACCOUNT" ? t("Service account") : t("User")}</td>
                  <td className="nowrap muted">{a.lastSeen ? formatDate(a.lastSeen) : "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <HowTo data={data} />
        </div>
      </Panel>
    </div>
  );
}

function HowTo({ data }: { data: SiteAdministrators }) {
  return (
    <div className="muted small stack">
      <p>
        {t(
          "Site administration is the plm-admin role in the identity provider (Keycloak: the plm realm, Users, Role mapping). A user's change shows here at their next sign-in. Service accounts get it from Service accounts › Can administer.",
        )}
      </p>
      {!data.enforced && (
        <p>{t("To refuse the rights to anyone but the built-in administrator, start the backend with PLM_ONLY_BUILT_IN_ADMIN=true.")}</p>
      )}
    </div>
  );
}
