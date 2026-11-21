import { Plus } from "lucide-react";
import { useMemo, useState } from "react";
import { Navigate, useParams } from "react-router";
import {
  useBrowsableContexts,
  useContextsOfKind,
  useManagedOrganizations,
  useMe,
  useOrganizationNames,
  useSite,
  type ContextKind,
} from "../api/queries";
import { t } from "../i18n/i18n";
import { ContextIcon, ErrorBanner, Loading, Panel, kindFromSlug, kindPlural, kindPluralInText } from "../components/common";
import { PageHeader } from "../components/Layout";
import { ContextTable, NewContextDialog, countOf } from "./ContextLists";
import { NotFoundPage } from "./NotFoundPage";

/** "New product", ...: the button that creates a context of [kind]. */
function newLabel(kind: ContextKind): string {
  switch (kind) {
    case "ORGANIZATION":
      return t("New organization");
    case "PRODUCT":
      return t("New product");
    case "LIBRARY":
      return t("New library");
    case "PROJECT":
      return t("New project");
    default:
      return t("New site");
  }
}

/** The empty list of [kind]. */
function noneYet(kind: ContextKind): string {
  switch (kind) {
    case "ORGANIZATION":
      return t("No organizations you can see yet.");
    case "PRODUCT":
      return t("No products you can see yet.");
    case "LIBRARY":
      return t("No libraries you can see yet.");
    case "PROJECT":
      return t("No projects you can see yet.");
    default:
      return t("No site you can see yet.");
  }
}

/** Every visible context of one kind: /browse/organizations, /browse/products, ... */
export function BrowsePage() {
  const kind = kindFromSlug(useParams().kind);
  if (!kind) return <NotFoundPage />;
  return <BrowseView key={kind} kind={kind} />;
}

function BrowseView({ kind }: { kind: ContextKind }) {
  const { data: contexts, isLoading, error } = useBrowsableContexts(kind);
  const { data: organizations } = useContextsOfKind(kind === "ORGANIZATION" ? undefined : "ORGANIZATION");
  const { data: me } = useMe();
  // The Organization column follows the user's "Show organization names" preference.
  const showOrganization = !!useOrganizationNames();
  const { data: site } = useSite();
  const managed = useManagedOrganizations();
  const [filter, setFilter] = useState("");
  const [orgFilter, setOrgFilter] = useState("");
  const [creating, setCreating] = useState(false);

  const byId = useMemo(() => new Map((organizations ?? []).map((o) => [o.id, o])), [organizations]);
  const shown = useMemo(() => {
    const f = filter.trim().toLowerCase();
    return (contexts ?? []).filter((c) => (!f || c.name.toLowerCase().includes(f)) && (!orgFilter || c.parentId === orgFilter));
  }, [contexts, filter, orgFilter]);

  // Organizations are made by site admins on the site; the rest by managers of an organization.
  const canCreate = kind === "ORGANIZATION" ? !!me?.siteAdmin && !!site : !!managed?.length;
  // Organizations are for administrators (site, or of an organization): others go home.
  if (kind === "ORGANIZATION" && managed && managed.length === 0) return <Navigate to="/" replace />;

  return (
    <>
      <PageHeader
        icon={<ContextIcon kind={kind} size={18} />}
        title={kindPlural(kind)}
        meta={
          contexts && (
            <span className="sub">
              {countOf(contexts.length, kind)} {kind === "ORGANIZATION" && !me?.siteAdmin ? t("you manage") : t("you can see")}
            </span>
          )
        }
        toolbar={
          canCreate ? (
            <button className="btn ghost" onClick={() => setCreating(true)}>
              <Plus size={15} aria-hidden />
              {newLabel(kind)}
            </button>
          ) : undefined
        }
      />
      <div className="content">
        <Panel
          title={kindPlural(kind)}
          note={
            contexts
              ? shown.length === contexts.length
                ? String(contexts.length)
                : t("{shown} of {total}", { shown: shown.length, total: contexts.length })
              : undefined
          }
          flush
          actions={
            <>
              {kind !== "ORGANIZATION" && (organizations?.length ?? 0) > 1 && (
                <select className="filter" aria-label={t("Organization")} value={orgFilter} onChange={(e) => setOrgFilter(e.target.value)}>
                  <option value="">{t("All organizations")}</option>
                  {organizations?.map((o) => (
                    <option key={o.id} value={o.id}>
                      {o.name}
                    </option>
                  ))}
                </select>
              )}
              <input
                className="filter"
                type="search"
                aria-label={t("Filter {kinds}", { kinds: kindPluralInText(kind) })}
                placeholder={t("Filter by name")}
                value={filter}
                onChange={(e) => setFilter(e.target.value)}
              />
            </>
          }
        >
          <ErrorBanner error={error} />
          {isLoading ? (
            <Loading />
          ) : !contexts?.length ? (
            <p className="empty">
              {noneYet(kind)}
              {canCreate ? ` ${t("Use {action} to create one.", { action: newLabel(kind) })}` : ""}
            </p>
          ) : shown.length === 0 ? (
            <p className="empty">{t("Nothing matches.")}</p>
          ) : (
            <ContextTable
              contexts={shown}
              label={kindPlural(kind)}
              organizations={kind === "ORGANIZATION" || !showOrganization ? undefined : byId}
            />
          )}
        </Panel>
      </div>
      {creating &&
        (kind === "ORGANIZATION" ? (
          <NewContextDialog kind={kind} parent={site ?? undefined} onClose={() => setCreating(false)} />
        ) : (
          <NewContextDialog kind={kind} parents={managed} onClose={() => setCreating(false)} />
        ))}
    </>
  );
}
