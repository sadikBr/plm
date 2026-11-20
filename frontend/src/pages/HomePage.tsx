import { Navigate } from "react-router";
import { useMe, useSite } from "../api/queries";
import { ErrorBanner, Loading, Panel } from "../components/common";

/** Sends users to the site context, or explains what to do if they aren't on any team yet. */
export function HomePage() {
  const { data: site, isLoading, error } = useSite();
  const { data: me } = useMe();

  if (isLoading) return <Loading />;
  if (error) return <ErrorBanner error={error} />;
  if (site) return <Navigate to={`/contexts/${site.id}`} replace />;

  return (
    <div className="content notice">
      <Panel title={`Welcome${me ? `, ${me.user.displayName}` : ""}`}>
        <p style={{ margin: 0 }}>
          You're signed in, but not on any team yet, so there's nothing to show. Ask a site administrator or a product
          manager to add you. Your username is <code>{me?.user.username}</code>.
        </p>
      </Panel>
    </div>
  );
}
