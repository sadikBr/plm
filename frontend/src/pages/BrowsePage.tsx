import { Plus } from "lucide-react";
import { useMemo, useState } from "react";
import { useParams } from "react-router";
import { useContextsOfKind, useManagedOrganizations, useMe, useSite, type ContextKind } from "../api/queries";
import { ContextIcon, ErrorBanner, Loading, Panel, kindFromSlug, kindLabel, kindPlural } from "../components/common";
import { PageHeader } from "../components/Layout";
import { ContextTable, NewContextDialog, countOf } from "./ContextLists";
import { NotFoundPage } from "./NotFoundPage";

/** Every visible context of one kind: /browse/organizations, /browse/products, ... */
export function BrowsePage() {
  const kind = kindFromSlug(useParams().kind);
  if (!kind) return <NotFoundPage />;
  return <BrowseView key={kind} kind={kind} />;
}

function BrowseView({ kind }: { kind: ContextKind }) {
  const { data: contexts, isLoading, error } = useContextsOfKind(kind);
  const { data: organizations } = useContextsOfKind(kind === "ORGANIZATION" ? undefined : "ORGANIZATION");
  const { data: me } = useMe();
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
  const label = kindLabel(kind).toLowerCase();

  return (
    <>
      <PageHeader
        icon={<ContextIcon kind={kind} size={18} />}
        title={kindPlural(kind)}
        meta={contexts && <span className="sub">{countOf(contexts.length, kind)} you can see</span>}
        toolbar={
          canCreate ? (
            <button className="btn ghost" onClick={() => setCreating(true)}>
              <Plus size={15} aria-hidden />
              New {label}
            </button>
          ) : undefined
        }
      />
      <div className="content">
        <Panel
          title={kindPlural(kind)}
          note={
            contexts ? (shown.length === contexts.length ? String(contexts.length) : `${shown.length} of ${contexts.length}`) : undefined
          }
          flush
          actions={
            <>
              {kind !== "ORGANIZATION" && (organizations?.length ?? 0) > 1 && (
                <select className="filter" aria-label="Organization" value={orgFilter} onChange={(e) => setOrgFilter(e.target.value)}>
                  <option value="">All organizations</option>
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
                aria-label={`Filter ${kindPlural(kind).toLowerCase()}`}
                placeholder="Filter by name"
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
              No {kindPlural(kind).toLowerCase()} you can see yet.
              {canCreate ? ` Use New ${label} to create one.` : ""}
            </p>
          ) : shown.length === 0 ? (
            <p className="empty">Nothing matches.</p>
          ) : (
            <ContextTable contexts={shown} label={kindPlural(kind)} organizations={kind === "ORGANIZATION" ? undefined : byId} />
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
