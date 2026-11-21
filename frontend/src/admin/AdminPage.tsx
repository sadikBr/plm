import { DocumentLinkRulesAdmin } from "../structure/DocumentLinks";
import { ScrollText, Settings } from "lucide-react";
import { Link, useSearchParams } from "react-router";
import { useMe } from "../api/queries";
import { Loading } from "../components/common";
import { PageHeader, Tabs } from "../components/Layout";
import { t } from "../i18n/i18n";
import { ActionsAdmin } from "./ActionsAdmin";
import { ChangesAdmin } from "./ChangesAdmin";
import { ScriptsAdmin } from "./ScriptsAdmin";
import { AutomationsAdmin } from "./AutomationsAdmin";
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
import { ProcessTemplatesAdmin } from "../workflow/ProcessEditor";

type Section =
  | "types"
  | "globals"
  | "lists"
  | "units"
  | "numbering"
  | "lifecycles"
  | "processes"
  | "changes"
  | "delete-rules"
  | "document-links"
  | "roles"
  | "teams"
  | "templates"
  | "document-templates"
  | "actions"
  | "rules"
  | "scripts"
  | "automations"
  | "webhooks"
  | "service-accounts"
  | "profiles"
  | "jobs"
  | "extensions"
  | "configuration";

const SECTIONS = (): [Section, string][] => [
  ["types", t("Types and attributes")],
  ["globals", t("Global attributes")],
  ["lists", t("Value lists")],
  ["units", t("Units")],
  ["numbering", t("Numbering")],
  ["lifecycles", t("Lifecycles")],
  ["processes", t("Processes")],
  ["changes", t("Changes")],
  ["delete-rules", t("Delete rules")],
  ["document-links", t("Part–document links")],
  ["roles", t("Team roles")],
  ["teams", t("Shared teams")],
  ["templates", t("Context templates")],
  ["document-templates", t("Document templates")],
  ["actions", t("Actions")],
  ["rules", t("Rules")],
  ["scripts", t("Scripts")],
  ["automations", t("Automations")],
  ["webhooks", t("Webhooks")],
  ["service-accounts", t("Service accounts")],
  ["profiles", t("Profiles")],
  ["jobs", t("Jobs")],
  ["extensions", t("Extensions")],
  ["configuration", t("Export / import")],
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
        <p className="empty">{t("Administration is for site administrators.")}</p>
      </div>
    );
  }

  return (
    <>
      <PageHeader
        icon={<Settings size={18} className="icon-muted" aria-hidden />}
        title={<span className="nm">{t("Administration")}</span>}
        meta={<span className="sub">{t("Changes apply to new forms and objects right away. Existing data stays valid.")}</span>}
        toolbar={
          <Link className="btn ghost" to="/audit">
            <ScrollText size={15} aria-hidden />
            {t("Audit report")}
          </Link>
        }
        tabs={<Tabs tabs={SECTIONS()} active={tab} onSelect={(key) => setParams({ tab: key })} />}
      />
      <div className="content">
        {tab === "types" && <TypesAdmin />}
        {tab === "globals" && <GlobalAttributesAdmin />}
        {tab === "lists" && <ValueListsAdmin />}
        {tab === "units" && <UnitsAdmin />}
        {tab === "numbering" && <NumberingAdmin />}
        {tab === "lifecycles" && <LifecyclesAdmin />}
        {tab === "processes" && <ProcessTemplatesAdmin />}
        {tab === "changes" && <ChangesAdmin />}
        {tab === "delete-rules" && <DeleteRulesAdmin />}
        {tab === "document-links" && <DocumentLinkRulesAdmin />}
        {tab === "roles" && <RolesAdmin />}
        {tab === "teams" && <SharedTeamsAdmin />}
        {tab === "templates" && <ContextTemplatesAdmin />}
        {tab === "document-templates" && <DocumentTemplatesAdmin />}
        {tab === "actions" && <ActionsAdmin />}
        {tab === "rules" && <RulesAdmin />}
        {tab === "scripts" && <ScriptsAdmin />}
        {tab === "automations" && <AutomationsAdmin />}
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
