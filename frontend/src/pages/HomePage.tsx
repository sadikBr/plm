import { useMe, useSite } from "../api/queries";
import { t } from "../i18n/i18n";
import { ErrorBanner, Loading, Panel } from "../components/common";
import { HomeDashboard } from "../reports/Dashboards";

/**
 * Where signing in lands: the person's home page (a dashboard they choose and arrange). Explains
 * what to do if they aren't on any team yet.
 */
export function HomePage() {
  const { data: site, isLoading, error } = useSite();
  const { data: me } = useMe();

  if (isLoading) return <Loading />;
  if (error) return <ErrorBanner error={error} />;
  // Members of any team get their home page (roadmap 3.8): their chosen dashboard, or the default.
  if (site) return <HomeDashboard />;

  return (
    <div className="content notice">
      <Panel title={me ? t("Welcome, {name}", { name: me.user.displayName }) : t("Welcome")}>
        <p style={{ margin: 0 }}>
          {t(
            "You're signed in, but not on any team yet, so there's nothing to show. Ask a site administrator or a product manager to add you.",
          )}{" "}
          {t("Your username is")} <code>{me?.user.username}</code>.
        </p>
      </Panel>
    </div>
  );
}
