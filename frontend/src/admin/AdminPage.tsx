import { Settings } from "lucide-react";
import { useSearchParams } from "react-router";
import { useMe } from "../api/queries";
import { Loading } from "../components/common";
import { PageHeader, Tabs } from "../components/Layout";
import { ActionsAdmin } from "./ActionsAdmin";
import { ExtensionsAdmin } from "./ExtensionsAdmin";
import { ConfigurationAdmin } from "./ConfigurationAdmin";
import { GlobalAttributesAdmin } from "./GlobalAttributesAdmin";
import { LifecyclesAdmin } from "./LifecyclesAdmin";
import { NumberingAdmin, RolesAdmin, UnitsAdmin, ValueListsAdmin } from "./ReferenceAdmin";
import { ContextTemplatesAdmin, SharedTeamsAdmin } from "./TeamsAndTemplatesAdmin";
import { TypesAdmin } from "./TypesAdmin";

type Section = "types" | "globals" | "lists" | "units" | "numbering" | "lifecycles" | "roles" | "teams" | "templates" | "actions" | "extensions" | "configuration";

const SECTIONS: [Section, string][] = [
  ["types", "Types and attributes"],
  ["globals", "Global attributes"],
  ["lists", "Value lists"],
  ["units", "Units"],
  ["numbering", "Numbering"],
  ["lifecycles", "Lifecycles"],
  ["roles", "Team roles"],
  ["teams", "Shared teams"],
  ["templates", "Context templates"],
  ["actions", "Actions"],
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
        tabs={<Tabs tabs={SECTIONS} active={tab} onSelect={(t) => setParams({ tab: t })} />}
      />
      <div className="content">
        {tab === "types" && <TypesAdmin />}
        {tab === "globals" && <GlobalAttributesAdmin />}
        {tab === "lists" && <ValueListsAdmin />}
        {tab === "units" && <UnitsAdmin />}
        {tab === "numbering" && <NumberingAdmin />}
        {tab === "lifecycles" && <LifecyclesAdmin />}
        {tab === "roles" && <RolesAdmin />}
        {tab === "teams" && <SharedTeamsAdmin />}
        {tab === "templates" && <ContextTemplatesAdmin />}
        {tab === "actions" && <ActionsAdmin />}
        {tab === "extensions" && <ExtensionsAdmin />}
        {tab === "configuration" && <ConfigurationAdmin />}
      </div>
    </>
  );
}
