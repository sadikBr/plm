import { ScrollText, Settings } from "lucide-react";
import { Link, useSearchParams } from "react-router";
import { useMe } from "../api/queries";
import { Loading } from "../components/common";
import { PageHeader, Tabs } from "../components/Layout";
import { ActionsAdmin } from "./ActionsAdmin";
import { DeleteRulesAdmin } from "./DeleteRulesAdmin";
import { DocumentTemplatesAdmin } from "./DocumentTemplatesAdmin";
import { ExtensionsAdmin } from "./ExtensionsAdmin";
import { RulesAdmin } from "./RulesAdmin";
import { JobsAdmin } from "./JobsAdmin";
import { ProfilesAdmin } from "./ProfilesAdmin";
import { ServiceAccountsAdmin } from "./ServiceAccountsAdmin";
import { WebhooksAdmin } from "./WebhooksAdmin";
import { ConfigurationAdmin } from "./ConfigurationAdmin";
import { GlobalAttributesAdmin } from "./GlobalAttributesAdmin";
import { LifecyclesAdmin } from "./LifecyclesAdmin";
import { NumberingAdmin, RolesAdmin, UnitsAdmin, ValueListsAdmin } from "./ReferenceAdmin";
import { ContextTemplatesAdmin, SharedTeamsAdmin } from "./TeamsAndTemplatesAdmin";
import { TypesAdmin } from "./TypesAdmin";

type Section =
  | "types"
  | "globals"
  | "lists"
  | "units"
  | "numbering"
  | "lifecycles"
  | "delete-rules"
  | "roles"
  | "teams"
  | "templates"
  | "document-templates"
  | "actions"
  | "rules"
  | "webhooks"
  | "service-accounts"
  | "profiles"
  | "jobs"
  | "extensions"
  | "configuration";

const SECTIONS: [Section, string][] = [
  ["types", "Types and attributes"],
  ["globals", "Global attributes"],
  ["lists", "Value lists"],
  ["units", "Units"],
  ["numbering", "Numbering"],
  ["lifecycles", "Lifecycles"],
  ["delete-rules", "Delete rules"],
  ["roles", "Team roles"],
  ["teams", "Shared teams"],
  ["templates", "Context templates"],
  ["document-templates", "Document templates"],
  ["actions", "Actions"],
  ["rules", "Rules"],
  ["webhooks", "Webhooks"],
  ["service-accounts", "Service accounts"],
  ["profiles", "Profiles"],
  ["jobs", "Jobs"],
  ["extensions", "Extensions"],
  ["configuration", "Export / import"],
];

/** Site administration: the data model and reference data, all without code. */
export function AdminPage() {
  const { data: me, isLoading } = useMe();
  const [params, setParams] = useSearchParams();
  const tab = (params.get("tab") as Section | null) ?? "types";

  if (isLoading) return <Loading />;
  if (!me?.siteAdmin) {
    return (
      <div className="content">
        <p className="empty">Administration is for site administrators.</p>
      </div>
    );
  }

  return (
    <>
      <PageHeader
        icon={<Settings size={18} className="icon-muted" aria-hidden />}
        title={<span className="nm">Administration</span>}
        meta={<span className="sub">Changes apply to new forms and objects right away. Existing data stays valid.</span>}
        toolbar={
          <Link className="btn ghost" to="/audit">
            <ScrollText size={15} aria-hidden />
            Audit report
          </Link>
        }
        tabs={<Tabs tabs={SECTIONS} active={tab} onSelect={(t) => setParams({ tab: t })} />}
      />
      <div className="content">
        {tab === "types" && <TypesAdmin />}
        {tab === "globals" && <GlobalAttributesAdmin />}
        {tab === "lists" && <ValueListsAdmin />}
        {tab === "units" && <UnitsAdmin />}
        {tab === "numbering" && <NumberingAdmin />}
        {tab === "lifecycles" && <LifecyclesAdmin />}
        {tab === "delete-rules" && <DeleteRulesAdmin />}
        {tab === "roles" && <RolesAdmin />}
        {tab === "teams" && <SharedTeamsAdmin />}
        {tab === "templates" && <ContextTemplatesAdmin />}
        {tab === "document-templates" && <DocumentTemplatesAdmin />}
        {tab === "actions" && <ActionsAdmin />}
        {tab === "rules" && <RulesAdmin />}
        {tab === "webhooks" && <WebhooksAdmin />}
        {tab === "service-accounts" && <ServiceAccountsAdmin />}
        {tab === "profiles" && <ProfilesAdmin />}
        {tab === "jobs" && <JobsAdmin />}
        {tab === "extensions" && <ExtensionsAdmin />}
        {tab === "configuration" && <ConfigurationAdmin />}
      </div>
    </>
  );
}
