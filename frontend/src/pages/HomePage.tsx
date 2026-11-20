import { Navigate } from "react-router";
import { useCanOpen, useContextsOfKind, useManagedOrganizations, useMe, useSite } from "../api/queries";
import { ErrorBanner, Loading, Panel, browsePath } from "../components/common";

/**
 * Where signing in lands: the site for site administrators, their organizations for organization
 * administrators, and for everyone else the first kind of container they work in. Explains what to
 * do if they aren't on any team yet.
 */
export function HomePage() {
  const { data: site, isLoading, error } = useSite();
  const { data: me } = useMe();
  const canOpen = useCanOpen();
  const managed = useManagedOrganizations();
  const products = useContextsOfKind(site ? "PRODUCT" : undefined);
  const libraries = useContextsOfKind(site ? "LIBRARY" : undefined);
  const projects = useContextsOfKind(site ? "PROJECT" : undefined);

  if (isLoading) return <Loading />;
  if (error) return <ErrorBanner error={error} />;
  if (site) {
    if (!canOpen) return <Loading />;
    if (canOpen(site)) return <Navigate to={`/contexts/${site.id}`} replace />;
    if (managed?.length) return <Navigate to={browsePath("ORGANIZATION")} replace />;
    if (!products.data || !libraries.data || !projects.data) return <Loading />;
    const kind =
      !products.data.length && libraries.data.length ? "LIBRARY" : !products.data.length && projects.data.length ? "PROJECT" : "PRODUCT";
    return <Navigate to={browsePath(kind)} replace />;
  }

  return (
    <div className="content notice">
      <Panel title={`Welcome${me ? `, ${me.user.displayName}` : ""}`}>
        <p style={{ margin: 0 }}>
          You're signed in, but not on any team yet, so there's nothing to show. Ask a site administrator or a product manager to add you.
          Your username is <code>{me?.user.username}</code>.
        </p>
      </Panel>
    </div>
  );
}
