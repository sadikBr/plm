import { FavoriteButton, SubscribeButton } from "../personal/Personal";
import { FilePlus, LayoutTemplate, Plus, ScrollText } from "lucide-react";
import { useEffect, useMemo, useState, type FormEvent, type ReactNode } from "react";
import { Link, Navigate, useNavigate, useSearchParams } from "react-router";
import { useMutation, useQuery } from "@tanstack/react-query";
import { api, isApiError, unwrap } from "../api/client";
import {
  ALLOWED_CHILDREN,
  HOLDS_OBJECTS,
  useChildren,
  useCanOpen,
  useManagedOrganizations,
  useContextById,
  useEffectivePreferences,
  useSaveAsTemplate,
  useCreateObject,
  useMe,
  useMembers,
  useObjects,
  usePermissions,
  useLayout,
  useTypeAttributes,
  useTypes,
  type Attributes,
  type Context,
  type ContextKind,
  type Permission,
  useShows,
} from "../api/queries";
import { AttributeForm, defaultValues, fieldErrors } from "../components/AttributeForm";
import {
  ContextIcon,
  Dialog,
  ErrorBanner,
  Loading,
  Panel,
  PrincipalName,
  formatDate,
  kindInText,
  kindLabel,
  kindPlural,
  kindPluralInText,
  typeLabel,
  browsePath,
} from "../components/common";
import { Breadcrumbs, PageHeader, Tabs, useIdParam } from "../components/Layout";
import { rememberVisit } from "../components/Navigator";
import { ObjectTable, useAttributeDefinitions, useCellText, type ColumnKey } from "../components/ObjectTable";
import { DEFAULT_VIEW, ViewBar, applyView, exportXlsx, type ViewDefinition } from "../components/TableViews";
import { AccessTab, TeamTab } from "./ContextAccess";
import { ContextTable, NewContextDialog, newContextTitle } from "./ContextLists";
import { BulkBar, BulkProgress } from "./Bulk";
import { FolderBar } from "./Folders";
import { PreferencesTab } from "./Preferences";
import { ContextTemplatesAdmin, SharedTeamsAdmin } from "../admin/TeamsAndTemplatesAdmin";
import { NotFoundPage } from "./NotFoundPage";
import { t, tn } from "../i18n/i18n";

type Tab =
  | "overview"
  | "contents"
  | "organizations"
  | "products"
  | "libraries"
  | "projects"
  | "team"
  | "access"
  | "preferences"
  | "teams"
  | "templates";

/** The tab listing an organization's (or the site's) children of one kind. */
const CHILD_TAB: Partial<Record<ContextKind, Tab>> = {
  ORGANIZATION: "organizations",
  PRODUCT: "products",
  LIBRARY: "libraries",
  PROJECT: "projects",
};
// Links from before the tabs were split by kind.
const OLD_TABS: Record<string, Tab> = { objects: "contents" };

/** Remounts per context, so page state (open dialogs, filters, form drafts) never leaks between contexts. */
export function ContextPage() {
  const id = useIdParam();
  return <ContextView key={id} id={id} />;
}

function ContextView({ id }: { id: string }) {
  const { data: context, error, isLoading } = useContextById(id);
  const { data: permissions } = usePermissions(context?.id);
  const { data: me } = useMe();
  const shows = useShows();
  const canOpen = useCanOpen();
  const managed = useManagedOrganizations();
  const [params, setParams] = useSearchParams();
  const [dialog, setDialog] = useState<"template" | null>(null);

  useEffect(() => {
    if (context) rememberVisit(context);
  }, [context]);

  if (isLoading || (context && !canOpen) || (context?.kind === "ORGANIZATION" && !me?.siteAdmin && !managed)) return <Loading />;
  if (isApiError(error, 404)) return <NotFoundPage what="context" />;
  if (error || !context) return <ErrorBanner error={error} />;
  // The site and organizations are for administrators; others are sent to their own work.
  // Not theirs to open (the site, an organization): their own work instead.
  if (!canOpen!(context)) return <Navigate to={browsePath("PRODUCT")} replace />;

  const holdsObjects = HOLDS_OBJECTS.has(context.kind);
  const childKinds = ALLOWED_CHILDREN[context.kind];
  const canManage = !!permissions?.has("MANAGE");
  // Managers save templates for their organization; site admins may also make them site-wide.
  const canSaveTemplate = context.kind !== "SITE" && (!!me?.siteAdmin || canManage);
  const isOrgAdmin = context.kind === "ORGANIZATION" && canManage;
  const tabs: [Tab, string][] = [
    ["overview", t("Overview")],
    ...(holdsObjects ? [["contents", t("Contents")] as [Tab, string]] : []),
    ...childKinds.map((k) => [CHILD_TAB[k]!, kindPlural(k)] as [Tab, string]),
    ...(shows("context.team") ? [["team", t("Team")] as [Tab, string]] : []),
    // Access rules and preferences are for whoever manages this context.
    ...(canManage && shows("context.access") ? [["access", t("Access")] as [Tab, string]] : []),
    ...(canManage && shows("context.preferences") ? [["preferences", t("Preferences")] as [Tab, string]] : []),
    ...(isOrgAdmin ? [["teams", t("Shared teams")] as [Tab, string], ["templates", t("Templates")] as [Tab, string]] : []),
  ];
  const asked = params.get("tab") ?? "";
  const requested = (OLD_TABS[asked] ?? asked) as Tab;
  // Each kind opens on what it's mostly used for: a product's contents, the site's organizations.
  const fallback: Tab = holdsObjects ? "contents" : context.kind === "SITE" ? "organizations" : "overview";
  const tab: Tab = tabs.some(([k]) => k === requested) ? requested : fallback;
  const select = (k: Tab) => setParams({ tab: k });
  // Organizations are created by site admins only; everything else needs MANAGE on the parent.
  const canCreateChildren = context.kind === "SITE" ? !!me?.siteAdmin : canManage;

  return (
    <>
      <PageHeader
        crumbs={context.parentId ? <Breadcrumbs contextId={context.parentId} current={context.name} /> : undefined}
        icon={<ContextIcon kind={context.kind} size={18} />}
        title={<span className="nm">{context.name}</span>}
        meta={
          <>
            <span className={`tag kind-${context.kind.toLowerCase()}`}>{kindLabel(context.kind)}</span>
            <span className="sub">
              {t("Created {date} by", { date: formatDate(context.createdAt) })} <PrincipalName id={context.createdBy} />
            </span>
            {context.kind !== "SITE" && (
              <span className="track-btns">
                <FavoriteButton kind="CONTEXT" targetId={context.id} label={context.name} />
                {holdsObjects && <SubscribeButton kind="CONTEXT" targetId={context.id} label={context.name} />}
              </span>
            )}
          </>
        }
        toolbar={
          canSaveTemplate || canManage ? (
            <>
              {canSaveTemplate && (
                <button className="btn ghost" onClick={() => setDialog("template")}>
                  <LayoutTemplate size={15} aria-hidden />
                  {t("Save as template")}
                </button>
              )}
              {(canManage || !!me?.siteAdmin) && context.kind !== "SITE" && (
                <Link className="btn ghost" to={`/audit?contextId=${context.id}`}>
                  <ScrollText size={15} aria-hidden />
                  {t("Audit report")}
                </Link>
              )}
            </>
          ) : undefined
        }
        tabs={<Tabs tabs={tabs} active={tab} onSelect={select} />}
      />
      <div className="content">
        {tab === "overview" && <OverviewTab context={context} permissions={permissions} onSelect={select} />}
        {tab === "contents" && <ObjectsTab context={context} canCreate={!!permissions?.has("CREATE")} />}
        {childKinds.map((k) => tab === CHILD_TAB[k] && <ChildrenTab key={k} parent={context} kind={k} canCreate={canCreateChildren} />)}
        {tab === "team" && <TeamTab context={context} canManage={canManage} />}
        {tab === "preferences" && canManage && <PreferencesTab contextId={context.id} />}
        {tab === "teams" && isOrgAdmin && <SharedTeamsAdmin organizationId={context.id} />}
        {tab === "templates" && isOrgAdmin && <ContextTemplatesAdmin organizationId={context.id} />}
        {tab === "access" && canManage && (
          // Rules on the site apply everywhere: only site admins change them.
          <AccessTab context={context} canChange={context.kind === "SITE" ? !!me?.siteAdmin : true} />
        )}
      </div>
      {dialog === "template" && <SaveAsTemplateDialog context={context} siteAdmin={!!me?.siteAdmin} onClose={() => setDialog(null)} />}
    </>
  );
}

// ---------- Overview ----------

const permissionLabels = (): [Permission, string][] => [
  ["READ", t("Read")],
  ["CREATE", t("Create")],
  ["MODIFY", t("Modify")],
  ["REVISE", t("Revise")],
  ["PROMOTE", t("Promote")],
  ["MANAGE", t("Manage")],
];

function OverviewTab({
  context,
  permissions,
  onSelect,
}: {
  context: Context;
  permissions?: Set<Permission>;
  onSelect: (tab: Tab) => void;
}) {
  const { data: parent } = useContextById(context.parentId && context.kind !== "ORGANIZATION" ? context.parentId : undefined);
  const canOpen = useCanOpen();
  const { data: children } = useChildren(context.id, !HOLDS_OBJECTS.has(context.kind));
  const { data: members } = useMembers(context.id);
  const childKinds = ALLOWED_CHILDREN[context.kind];

  return (
    <div className="overview">
      <Panel title={t("Details")}>
        <dl className="props">
          <Prop label={t("Name")}>{context.name}</Prop>
          <Prop label={t("Kind")}>{kindLabel(context.kind)}</Prop>
          {parent && (
            <Prop label={t("Organization")}>
              {canOpen?.(parent) ? <Link to={`/contexts/${parent.id}`}>{parent.name}</Link> : parent.name}
            </Prop>
          )}
          <Prop label={t("Created")}>
            {t("{date} by", { date: formatDate(context.createdAt) })} <PrincipalName id={context.createdBy} />
          </Prop>
          <Prop label={t("Team")}>
            {members ? (
              <button className="link-btn" onClick={() => onSelect("team")}>
                {tn("{n} direct member", "{n} direct members", members.length)}
              </button>
            ) : (
              "—"
            )}
          </Prop>
          <Prop label={t("Your access")}>
            <span className="perm-list">
              {permissionLabels()
                .filter(([p]) => permissions?.has(p))
                .map(([p, label]) => (
                  <span key={p} className="tag">
                    {label}
                  </span>
                ))}
            </span>
          </Prop>
        </dl>
      </Panel>
      {childKinds.length > 0 && (
        <Panel title={t("Contains")}>
          <div className="stat-tiles">
            {childKinds.map((k) => {
              const n = children?.filter((c) => c.kind === k).length;
              return (
                <button key={k} className="stat-tile" onClick={() => onSelect(CHILD_TAB[k]!)}>
                  <span className={`kind kind-${k.toLowerCase()}`}>
                    <ContextIcon kind={k} size={16} />
                  </span>
                  <span className="stat-num">{n ?? "…"}</span>
                  <span className="stat-label">{kindPlural(k)}</span>
                </button>
              );
            })}
          </div>
        </Panel>
      )}
      {HOLDS_OBJECTS.has(context.kind) && <RecentObjects context={context} onSelect={onSelect} />}
    </div>
  );
}

function Prop({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="prop">
      <dt>{label}</dt>
      <dd>{children}</dd>
    </div>
  );
}

function RecentObjects({ context, onSelect }: { context: Context; onSelect: (tab: Tab) => void }) {
  const objects = useObjects(context.id, 5);
  const rows = objects.data?.pages[0] ?? [];
  return (
    <Panel
      title={t("Objects")}
      flush
      actions={
        <button className="btn small" onClick={() => onSelect("contents")}>
          {t("Open contents")}
        </button>
      }
    >
      {objects.isLoading ? (
        <Loading />
      ) : rows.length === 0 ? (
        <p className="empty">{t("No objects yet.")}</p>
      ) : (
        <ObjectTable objects={rows} label={t("Latest objects")} />
      )}
    </Panel>
  );
}

// ---------- Children of one kind (an organization's products, the site's organizations) ----------

function ChildrenTab({ parent, kind, canCreate }: { parent: Context; kind: ContextKind; canCreate: boolean }) {
  const { data: children, isLoading, error } = useChildren(parent.id);
  const [creating, setCreating] = useState(false);
  const [filter, setFilter] = useState("");
  const ofKind = children?.filter((c) => c.kind === kind) ?? [];
  const f = filter.trim().toLowerCase();
  const shown = f ? ofKind.filter((c) => c.name.toLowerCase().includes(f)) : ofKind;

  return (
    <>
      <Panel
        title={kindPlural(kind)}
        note={children ? String(ofKind.length) : undefined}
        flush
        actions={
          <>
            {ofKind.length > 5 && (
              <input
                className="filter"
                type="search"
                aria-label={t("Filter {kinds}", { kinds: kindPluralInText(kind) })}
                placeholder={t("Filter by name")}
                value={filter}
                onChange={(e) => setFilter(e.target.value)}
              />
            )}
            {canCreate && (
              <button className="btn small primary" onClick={() => setCreating(true)}>
                <Plus size={14} aria-hidden />
                {newContextTitle(kind)}
              </button>
            )}
          </>
        }
      >
        <ErrorBanner error={error} />
        {isLoading ? (
          <Loading />
        ) : ofKind.length === 0 ? (
          <p className="empty">{t("No {kinds} here that you can see.", { kinds: kindPluralInText(kind) })}</p>
        ) : (
          <ContextTable contexts={shown} label={kindPlural(kind)} />
        )}
      </Panel>
      {creating && <NewContextDialog kind={kind} parent={parent} onClose={() => setCreating(false)} />}
    </>
  );
}

// ---------- Objects ----------

/** The order of the New buttons. */
const BASE_CLASS_ORDER = ["PART", "DOCUMENT"];

/** "New part", or "New part in Bike" when [contextName] is given. */
function newObjectLabel(baseClass: string, contextName?: string): string {
  const name = contextName ?? "";
  switch (baseClass) {
    case "PART":
      return contextName ? t("New part in {context}", { context: name }) : t("New part");
    case "DOCUMENT":
      return contextName ? t("New document in {context}", { context: name }) : t("New document");
    default:
      return contextName
        ? t("New {kind} in {context}", { kind: baseClass.toLowerCase(), context: name })
        : t("New {kind}", { kind: baseClass.toLowerCase() });
  }
}

function ObjectsTab({ context, canCreate }: { context: Context; canCreate: boolean }) {
  const { data: types } = useTypes();
  // Folders (roadmap 3.4): the folder being looked at is in the address, so it survives reloads.
  const [params, setParams] = useSearchParams();
  const folder = params.get("folder") ?? undefined;
  const openFolder = (id?: string) => setParams(id ? { tab: "contents", folder: id } : { tab: "contents" });
  const [creating, setCreating] = useState<string | null>(null);
  // One New button per kind of thing there are types for: New part, New document.
  const baseClasses = [...new Set(types?.filter((t) => t.instantiable).map((t) => t.baseClass))].sort(
    (a, b) => BASE_CLASS_ORDER.indexOf(a) - BASE_CLASS_ORDER.indexOf(b),
  );
  const { data: prefs } = useEffectivePreferences(context.id);
  const objects = useObjects(context.id, (prefs?.get("ui.tablePageSize") as number | undefined) ?? 50, folder ?? "");
  // Table views (roadmap 3.7): columns, filters and sort; saved views and Excel export.
  const [view, setView] = useState<ViewDefinition>(DEFAULT_VIEW);
  const [exporting, setExporting] = useState<unknown>(null);
  // Mass operations (roadmap 3.5): ticked rows, and the job running on them.
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [bulkJob, setBulkJob] = useState<string | null>(null);
  const rows = useMemo(() => objects.data?.pages.flat() ?? [], [objects.data]);
  const presentTypes = useMemo(() => [...new Set(rows.map((o) => o.typeId))], [rows]);
  const definitions = useAttributeDefinitions(presentTypes);
  const text = useCellText(definitions);
  const shown = useMemo(() => applyView(rows, view, text), [rows, view, text]);
  const doExport = () => {
    setExporting(true);
    exportXlsx(context.name, view.columns as ColumnKey[], shown, definitions, text).then(
      () => setExporting(null),
      (e: unknown) => setExporting(e),
    );
  };

  return (
    <Panel
      title={t("Objects")}
      note={objects.data ? `${rows.length}${objects.hasNextPage ? "+" : ""}` : undefined}
      flush
      actions={
        <>
          {canCreate &&
            baseClasses.map((bc) => (
              <button key={bc} className="btn small primary" onClick={() => setCreating(bc)}>
                {bc === "DOCUMENT" ? <FilePlus size={14} aria-hidden /> : <Plus size={14} aria-hidden />}
                {newObjectLabel(bc)}
              </button>
            ))}
        </>
      }
    >
      {creating && <NewObjectDialog context={context} folderId={folder} baseClass={creating} onClose={() => setCreating(null)} />}
      <FolderBar contextId={context.id} contextName={context.name} folderId={folder} onOpen={openFolder} canCreate={canCreate} />
      {bulkJob && (
        <div style={{ padding: 12 }}>
          <BulkProgress jobId={bulkJob} onClose={() => setBulkJob(null)} />
        </div>
      )}
      {selected.size > 0 && (
        <BulkBar
          objects={rows.filter((o) => selected.has(o.id))}
          contextId={context.id}
          folderId={folder}
          onClear={() => setSelected(new Set())}
          onStarted={setBulkJob}
        />
      )}
      <ViewBar
        table="objects"
        view={view}
        onChange={setView}
        definitions={definitions}
        presentTypes={presentTypes}
        onExport={doExport}
        exporting={exporting === true}
      />
      <ErrorBanner error={objects.error ?? (exporting !== true ? exporting : null)} />
      {objects.isLoading ? (
        <Loading />
      ) : rows.length === 0 ? (
        <p className="empty">{folder ? t("No objects in this folder.") : t("No objects here yet.")}</p>
      ) : (
        <ObjectTable
          objects={shown}
          selected={selected}
          onSelect={setSelected}
          columns={view.columns as ColumnKey[]}
          sort={{ column: (view.sortColumn ?? "number") as ColumnKey, desc: !!view.sortDescending }}
          onSort={(s) => setView({ ...view, sortColumn: s.column, sortDescending: s.desc })}
          footer={
            <>
              <span>
                {shown.length === rows.length
                  ? tn("{n} object", "{n} objects", rows.length)
                  : tn("{shown} of {n} object", "{shown} of {n} objects", rows.length, { shown: shown.length })}
              </span>
              {objects.hasNextPage && (
                <button className="btn small" onClick={() => void objects.fetchNextPage()} disabled={objects.isFetchingNextPage}>
                  {t("Load more")}
                </button>
              )}
            </>
          }
        />
      )}
    </Panel>
  );
}

function NewObjectDialog({
  context,
  folderId,
  baseClass,
  onClose,
}: {
  context: Context;
  folderId?: string;
  baseClass: string;
  onClose: () => void;
}) {
  const { data: allTypes } = useTypes();
  // Abstract types only group subtypes: they can't be created.
  const types = allTypes?.filter((t) => t.instantiable && t.baseClass === baseClass);
  const create = useCreateObject();
  const navigate = useNavigate();
  const [typeId, setTypeId] = useState("");
  // Templates (roadmap 3.3): a new object can start from one, with its values and files.
  const { data: allTemplates } = useQuery({
    queryKey: ["documentTemplates", context.id],
    queryFn: () => unwrap(api.GET("/api/v1/document-templates", { params: { query: { contextId: context.id } } })),
  });
  const templates = allTemplates?.filter((t) => types?.some((ty) => ty.id === t.typeId));
  const [templateId, setTemplateId] = useState("");
  const template = templates?.find((t) => t.id === templateId);
  const fromTemplate = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/api/v1/objects/from-template", {
          body: { templateId, contextId: context.id, name, number: number.trim() || null, attributes: {} },
        }),
      ),
    onSuccess: async (obj) => {
      if (folderId)
        await unwrap(
          api.POST("/api/v1/objects/{id}/move", { params: { path: { id: obj.id } }, body: { contextId: context.id, folderId } }),
        );
      onClose();
      navigate(`/objects/${obj.id}`);
    },
  });
  const [name, setName] = useState("");
  const [number, setNumber] = useState("");
  const chosenType = typeId || types?.[0]?.id;
  const { data: definitions } = useTypeAttributes(chosenType);
  const { data: layout } = useLayout(chosenType, "CREATE");
  // Edits belong to the type they were made for; switching type starts again from its defaults.
  const [edits, setEdits] = useState<{
    typeId: string;
    values: Attributes;
  }>();
  const values = edits && edits.typeId === chosenType ? edits.values : defaultValues(definitions ?? []);
  const formId = "new-object-form";

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (template) {
      fromTemplate.mutate();
      return;
    }
    if (!chosenType) return;
    create.mutate(
      {
        typeId: chosenType,
        contextId: context.id,
        name,
        number: number.trim() || null,
        attributes: values,
        folderId: folderId ?? null,
      },
      {
        onSuccess: (obj) => {
          onClose();
          navigate(`/objects/${obj.id}`);
        },
      },
    );
  };

  return (
    <Dialog
      open
      title={newObjectLabel(baseClass, context.name)}
      onClose={onClose}
      footer={
        <>
          <button className="btn" type="button" onClick={onClose}>
            {t("Cancel")}
          </button>
          <button className="btn primary" type="submit" form={formId} disabled={create.isPending || fromTemplate.isPending || !name.trim()}>
            {t("Create")}
          </button>
        </>
      }
    >
      <form id={formId} onSubmit={submit} noValidate style={{ display: "grid", gap: 12 }}>
        <ErrorBanner error={create.error ?? fromTemplate.error} />
        <div className="section-label">{t("Identity")}</div>
        <div className="attr-form">
          {!!templates?.length && (
            <div className="field wide">
              <label htmlFor="obj-template">{t("Start from")}</label>
              <select id="obj-template" value={templateId} onChange={(e) => setTemplateId(e.target.value)}>
                <option value="">{t("Blank")}</option>
                {templates.map((t) => (
                  <option key={t.id} value={t.id}>
                    {t.name}
                    {t.fileName ? ` (${t.fileName})` : ""}
                  </option>
                ))}
              </select>
              {template && (
                <span className="hint">
                  {t("A copy of {number} {version}: its type, values and files. Change them after creating.", {
                    number: template.number,
                    version: template.version,
                  })}
                </span>
              )}
            </div>
          )}
          {!template && (
            <div className="field">
              <label htmlFor="obj-type">{t("Type")}</label>
              <select id="obj-type" value={chosenType ?? ""} onChange={(e) => setTypeId(e.target.value)}>
                {types?.map((t) => (
                  <option key={t.id} value={t.id}>
                    {typeLabel(t)}
                  </option>
                ))}
              </select>
            </div>
          )}
          <div className="field">
            <label htmlFor="obj-number">{t("Number")}</label>
            <input id="obj-number" value={number} onChange={(e) => setNumber(e.target.value)} placeholder={t("Assigned automatically")} />
          </div>
          <div className="field wide">
            <div className="label-row">
              <label htmlFor="obj-name">{t("Name")}</label>
              <span aria-hidden className="required">
                *
              </span>
            </div>
            <input id="obj-name" value={name} onChange={(e) => setName(e.target.value)} required autoFocus />
          </div>
        </div>
        {!template && definitions && layout && chosenType && (
          <AttributeForm
            idPrefix="new"
            definitions={definitions}
            groups={layout.groups}
            values={values}
            onChange={(next) => setEdits({ typeId: chosenType, values: next })}
            errors={fieldErrors(create.error)}
          />
        )}
      </form>
    </Dialog>
  );
}

function SaveAsTemplateDialog({ context, siteAdmin, onClose }: { context: Context; siteAdmin: boolean; onClose: () => void }) {
  const save = useSaveAsTemplate(context.id);
  // Organization templates are always site-wide (organizations are created on the site).
  const [siteWide, setSiteWide] = useState(context.kind === "ORGANIZATION");
  const [name, setName] = useState(() => t("{name} template", { name: context.name }));
  const [description, setDescription] = useState("");
  const [saved, setSaved] = useState(false);
  return (
    <Dialog
      open
      title={t("Save {name} as a template", { name: context.name })}
      onClose={onClose}
      footer={
        saved ? (
          <button className="btn primary" onClick={onClose}>
            {t("Done")}
          </button>
        ) : (
          <>
            <button className="btn" type="button" onClick={onClose}>
              {t("Cancel")}
            </button>
            <button className="btn primary" type="submit" form="template-form" disabled={save.isPending || !name.trim()}>
              {t("Save template")}
            </button>
          </>
        )
      }
    >
      <form
        id="template-form"
        noValidate
        style={{ display: "grid", gap: 12 }}
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate(
            {
              name: name.trim(),
              description: description.trim() || null,
              siteWide,
            },
            { onSuccess: () => setSaved(true) },
          );
        }}
      >
        <ErrorBanner error={save.error} />
        {saved ? (
          <p style={{ margin: 0 }}>
            {t("Saved. New {kinds} can now start from", { kinds: kindPluralInText(context.kind) })} <strong>{name}</strong>.
          </p>
        ) : (
          <>
            <p className="muted" style={{ margin: 0 }}>
              {t("Captures this {kind}'s own access rules, team members and attached shared teams. Its objects are not copied.", {
                kind: kindInText(context.kind),
              })}
            </p>
            <div className="field">
              <label htmlFor="template-name">{t("Template name")}</label>
              <input id="template-name" value={name} onChange={(e) => setName(e.target.value)} autoFocus />
            </div>
            <div className="field">
              <label htmlFor="template-description">{t("Description")}</label>
              <input id="template-description" value={description} onChange={(e) => setDescription(e.target.value)} />
            </div>
            {context.kind !== "ORGANIZATION" &&
              (siteAdmin ? (
                <label className="check">
                  <input type="checkbox" checked={siteWide} onChange={(e) => setSiteWide(e.target.checked)} />
                  {t("Site-wide: usable in every organization (otherwise only in this {kind}'s organization)", {
                    kind: kindInText(context.kind),
                  })}
                </label>
              ) : (
                <p className="muted" style={{ margin: 0 }}>
                  {t("It will belong to this {kind}'s organization and be usable only there.", { kind: kindInText(context.kind) })}
                </p>
              ))}
          </>
        )}
      </form>
    </Dialog>
  );
}
