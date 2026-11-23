import { DirectoryAdmin } from "./DirectoryAdmin";
import { MigrationAdmin } from "./MigrationAdmin";
import { DistributionAdmin } from "../integration/Distribution";
import { SecurityAdmin } from "../security/Security";
import { RetentionAdmin } from "../retention/Retention";
import { AuditAdmin } from "../audit/AuditAdmin";
import { FileSitesAdmin } from "../sites/FileSites";
import { OfficeAdmin } from "./OfficeAdmin";
import { DocumentLinkRulesAdmin } from "../structure/DocumentLinks";
import {
  ArrowLeftRight,
  Bot,
  Code2,
  FileSpreadsheet,
  FileText,
  FolderTree,
  Gavel,
  Globe,
  HardDrive,
  Hash,
  IdCard,
  Layers,
  LayoutTemplate,
  Link2,
  Lock,
  Factory,
  Network,
  PackageOpen,
  ListChecks,
  ListOrdered,
  Puzzle,
  Repeat,
  Ruler,
  ScrollText,
  Search,
  Send,
  Settings,
  Shapes,
  ShieldCheck,
  Timer,
  Trash2,
  UserCog,
  Users,
  Webhook,
  Workflow,
  X,
  Zap,
  ClipboardList,
} from "lucide-react";
import { useState, type ReactNode } from "react";
import { Link, useSearchParams } from "react-router";
import { useMe } from "../api/queries";
import { Loading } from "../components/common";
import { PageHeader } from "../components/Layout";
import { t } from "../i18n/i18n";
import { ActionsAdmin } from "./ActionsAdmin";
import { ChangesAdmin } from "./ChangesAdmin";
import { ScriptsAdmin } from "./ScriptsAdmin";
import { AutomationsAdmin } from "./AutomationsAdmin";
import { PublishingAdmin } from "./PublishingAdmin";
import { ClassificationAdmin } from "../classification/Classification";
import { DeleteRulesAdmin } from "./DeleteRulesAdmin";
import { DocumentTemplatesAdmin } from "./DocumentTemplatesAdmin";
import { ExtensionsAdmin } from "./ExtensionsAdmin";
import { RulesAdmin } from "./RulesAdmin";
import { JobsAdmin } from "./JobsAdmin";
import { ProfilesAdmin } from "./ProfilesAdmin";
import { ServiceAccountsAdmin } from "./ServiceAccountsAdmin";
import { SiteAdminsAdmin, SiteAdminsNotice } from "./SiteAdminsAdmin";
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
  | "publishing"
  | "office"
  | "classification"
  | "webhooks"
  | "site-admins"
  | "service-accounts"
  | "directory"
  | "migration"
  | "distribution"
  | "security"
  | "retention"
  | "audit"
  | "file-sites"
  | "profiles"
  | "jobs"
  | "extensions"
  | "configuration";

type Entry = [Section, string, string, ReactNode];

/** Every setting, grouped the way administrators think about them, with what each is for. */
const GROUPS = (): [string, Entry[]][] => [
  [
    t("Data model"),
    [
      ["types", t("Types and attributes"), t("Kinds of parts, documents and changes, and their fields"), <Shapes key="i" size={15} />],
      ["globals", t("Global attributes"), t("Fields shared by several types"), <Globe key="i" size={15} />],
      ["lists", t("Value lists"), t("Choices for list fields"), <ListOrdered key="i" size={15} />],
      ["units", t("Units"), t("Units of measure and conversions"), <Ruler key="i" size={15} />],
      ["numbering", t("Numbering"), t("How new objects are numbered"), <Hash key="i" size={15} />],
      ["classification", t("Classification"), t("The class tree and class attributes"), <FolderTree key="i" size={15} />],
    ],
  ],
  [
    t("Lifecycle and change"),
    [
      ["lifecycles", t("Lifecycles"), t("States and transitions"), <Repeat key="i" size={15} />],
      ["processes", t("Processes"), t("Workflows: reviews, approvals, tasks"), <Workflow key="i" size={15} />],
      ["changes", t("Changes"), t("Change templates, association rules, release gate"), <ClipboardList key="i" size={15} />],
      ["delete-rules", t("Delete rules"), t("What may be deleted, and by whom"), <Trash2 key="i" size={15} />],
      ["document-links", t("Part–document links"), t("Which documents describe which parts"), <Link2 key="i" size={15} />],
    ],
  ],
  [
    t("People and access"),
    [
      ["site-admins", t("Site administrators"), t("Who administers the site"), <ShieldCheck key="i" size={15} />],
      ["roles", t("Team roles"), t("Roles people hold on teams"), <UserCog key="i" size={15} />],
      ["teams", t("Shared teams"), t("Teams reused across products"), <Users key="i" size={15} />],
      ["audit", t("Audit"), t("Which events are recorded, and for how long"), <ScrollText key="i" size={15} />],
      ["retention", t("Retention and legal hold"), t("Purge old iterations, archive, freeze for litigation"), <Gavel key="i" size={15} />],
      ["security", t("Security labels"), t("Export control and other labels, clearances, agreements"), <Lock key="i" size={15} />],
      ["directory", t("Directory"), t("People and groups from the identity provider"), <Network key="i" size={15} />],
      ["templates", t("Context templates"), t("Ready-made products, libraries and projects"), <LayoutTemplate key="i" size={15} />],
      ["profiles", t("Profiles"), t("What each group of people sees"), <IdCard key="i" size={15} />],
      ["service-accounts", t("Service accounts"), t("Other systems that call the API"), <Bot key="i" size={15} />],
    ],
  ],
  [
    t("Automation and integration"),
    [
      ["actions", t("Actions"), t("Buttons on objects that run something"), <Zap key="i" size={15} />],
      ["rules", t("Rules"), t("Checks before an operation goes ahead"), <ListChecks key="i" size={15} />],
      ["scripts", t("Scripts"), t("Server-side scripts"), <Code2 key="i" size={15} />],
      ["automations", t("Automations"), t("When this happens, do that"), <Layers key="i" size={15} />],
      ["webhooks", t("Webhooks"), t("Tell other systems about events"), <Webhook key="i" size={15} />],
      ["distribution", t("ERP distribution"), t("Which objects go to which ERP plants"), <Factory key="i" size={15} />],
      ["file-sites", t("File sites"), t("Copies of the vault near remote offices"), <HardDrive key="i" size={15} />],
      ["jobs", t("Jobs"), t("Background work and schedules"), <Timer key="i" size={15} />],
      ["extensions", t("Extensions"), t("Installed extension packages"), <Puzzle key="i" size={15} />],
      ["migration", t("Migration"), t("Bring data in from another PLM system"), <PackageOpen key="i" size={15} />],
    ],
  ],
  [
    t("Documents and output"),
    [
      ["document-templates", t("Document templates"), t("Starting points for new documents"), <FileText key="i" size={15} />],
      ["publishing", t("Publishing"), t("PDFs, viewables and watermarks"), <Send key="i" size={15} />],
      ["office", t("Office files"), t("Word, Excel and PowerPoint properties"), <FileSpreadsheet key="i" size={15} />],
      ["configuration", t("Export / import"), t("Move settings between sites"), <ArrowLeftRight key="i" size={15} />],
    ],
  ],
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

  const entries = GROUPS().flatMap(([, list]) => list);
  const current = entries.find((e) => e[0] === tab) ?? entries[0]!;
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
      />
      <div className="admin-hub">
        <AdminMenu active={tab} onSelect={(key) => setParams({ tab: key })} />
        <div className="admin-pane">
          <div className="admin-pane-head">
            <span className="admin-pane-icon" aria-hidden>
              {current[3]}
            </span>
            <div>
              <h2>{current[1]}</h2>
              <p className="muted small">{current[2]}</p>
            </div>
          </div>
          <div className="content">
            {tab !== "site-admins" && <SiteAdminsNotice onOpen={() => setParams({ tab: "site-admins" })} />}
            {tab === "site-admins" && <SiteAdminsAdmin />}
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
            {tab === "publishing" && <PublishingAdmin />}
            {tab === "office" && <OfficeAdmin />}
            {tab === "classification" && <ClassificationAdmin />}
            {tab === "webhooks" && <WebhooksAdmin />}
            {tab === "service-accounts" && <ServiceAccountsAdmin />}
            {tab === "directory" && <DirectoryAdmin />}
            {tab === "migration" && <MigrationAdmin />}
            {tab === "distribution" && <DistributionAdmin />}
            {tab === "security" && <SecurityAdmin />}
            {tab === "retention" && <RetentionAdmin />}
            {tab === "audit" && <AuditAdmin />}
            {tab === "file-sites" && <FileSitesAdmin />}
            {tab === "profiles" && <ProfilesAdmin />}
            {tab === "jobs" && <JobsAdmin />}
            {tab === "extensions" && <ExtensionsAdmin />}
            {tab === "configuration" && <ConfigurationAdmin />}
          </div>
        </div>
      </div>
    </>
  );
}

/** The settings, grouped, with a filter: the same names as before, found by what they're for too. */
function AdminMenu({ active, onSelect }: { active: Section; onSelect: (s: Section) => void }) {
  const [filter, setFilter] = useState("");
  const q = filter.trim().toLowerCase();
  const groups = GROUPS()
    .map(([name, list]) => [name, list.filter(([, label, about]) => !q || `${label} ${about}`.toLowerCase().includes(q))] as const)
    .filter(([, list]) => list.length);
  return (
    <aside className="admin-menu">
      <div className="nav-filter">
        <Search size={13} aria-hidden />
        <input
          aria-label={t("Find a setting")}
          placeholder={t("Find a setting")}
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
        />
        {filter && (
          <button className="nav-clear" aria-label={t("Clear")} onClick={() => setFilter("")}>
            <X size={12} />
          </button>
        )}
      </div>
      <nav aria-label={t("Sections")}>
        {groups.map(([name, list]) => (
          <div key={name} className="admin-group">
            <div className="admin-group-title">{name}</div>
            {list.map(([key, label, about, icon]) => (
              <a
                key={key}
                href={`?tab=${key}`}
                title={about}
                className={active === key ? "admin-item active" : "admin-item"}
                aria-current={active === key ? "page" : undefined}
                onClick={(e) => {
                  e.preventDefault();
                  onSelect(key);
                }}
              >
                <span className="admin-item-icon" aria-hidden>
                  {icon}
                </span>
                {label}
              </a>
            ))}
          </div>
        ))}
        {!groups.length && <p className="nav-empty">{t("No setting matches.")}</p>}
      </nav>
    </aside>
  );
}
