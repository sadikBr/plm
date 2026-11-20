import { FilePlus, FolderPlus, LayoutTemplate } from "lucide-react";
import { useEffect, useMemo, useState, type FormEvent } from "react";
import { Link, useNavigate, useSearchParams } from "react-router";
import { isApiError } from "../api/client";
import {
  ALLOWED_CHILDREN,
  HOLDS_OBJECTS,
  useChildren,
  useContextById,
  useContextTemplates,
  useEffectivePreferences,
  useSaveAsTemplate,
  useCreateContext,
  useCreateObject,
  useMe,
  useObjects,
  usePermissions,
  useLayout,
  useTypeAttributes,
  useTypes,
  type Attributes,
  type Context,
  type ContextKind,
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
  kindLabel,
} from "../components/common";
import { Breadcrumbs, PageHeader, Tabs, useIdParam } from "../components/Layout";
import { rememberVisit } from "../components/Navigator";
import { ObjectTable } from "../components/ObjectTable";
import { AccessTab, TeamTab } from "./ContextAccess";
import { PreferencesTab } from "./Preferences";
import { NotFoundPage } from "./NotFoundPage";

type Tab = "objects" | "contexts" | "team" | "access" | "preferences";

/** Remounts per context, so page state (open dialogs, filters, form drafts) never leaks between contexts. */
export function ContextPage() {
  const id = useIdParam();
  return <ContextView key={id} id={id} />;
}

function ContextView({ id }: { id: string }) {
  const { data: context, error, isLoading } = useContextById(id);
  const { data: permissions } = usePermissions(context?.id);
  const { data: me } = useMe();
  const [params, setParams] = useSearchParams();
  const [dialog, setDialog] = useState<"object" | "context" | "template" | null>(null);

  useEffect(() => {
    if (context) rememberVisit(context);
  }, [context]);

  if (isLoading) return <Loading />;
  if (isApiError(error, 404)) return <NotFoundPage what="context" />;
  if (error || !context) return <ErrorBanner error={error} />;

  const holdsObjects = HOLDS_OBJECTS.has(context.kind);
  const childKinds = ALLOWED_CHILDREN[context.kind];
  const tab = (params.get("tab") as Tab | null) ?? (holdsObjects ? "objects" : "contexts");
  const tabs: [Tab, string][] = [
    ...(holdsObjects ? [["objects", "Objects"] as [Tab, string]] : []),
    ...(childKinds.length > 0 ? [["contexts", "Contexts"] as [Tab, string]] : []),
    ["team", "Team"],
    // Access rules are for whoever manages this context.
    ...(permissions?.has("MANAGE") ? [["access", "Access"] as [Tab, string], ["preferences", "Preferences"] as [Tab, string]] : []),
  ];
  const canCreateObject = holdsObjects && !!permissions?.has("CREATE");
  // Organizations are created by site admins only; everything else needs MANAGE on the parent.
  const canCreateContext = childKinds.length > 0 && (context.kind === "SITE" ? !!me?.siteAdmin : !!permissions?.has("MANAGE"));
  const newContextLabel = `New ${childKinds.length === 1 ? kindLabel(childKinds[0]!).toLowerCase() : "context"}`;

  return (
    <>
      <PageHeader
        crumbs={context.parentId ? <Breadcrumbs contextId={context.parentId} current={context.name} /> : undefined}
        icon={<ContextIcon kind={context.kind} size={18} />}
        title={<span className="nm">{context.name}</span>}
        meta={
          <>
            <span className="tag">{kindLabel(context.kind)}</span>
            <span className="sub">
              Created {formatDate(context.createdAt)} by <PrincipalName id={context.createdBy} />
            </span>
          </>
        }
        toolbar={
          canCreateObject || canCreateContext || (me?.siteAdmin && context.kind !== "SITE") ? (
            <>
              {canCreateObject && (
                <button className="btn ghost" onClick={() => setDialog("object")}>
                  <FilePlus size={15} aria-hidden />
                  New object
                </button>
              )}
              {canCreateContext && (
                <button className="btn ghost" onClick={() => setDialog("context")}>
                  <FolderPlus size={15} aria-hidden />
                  {newContextLabel}
                </button>
              )}
              {me?.siteAdmin && context.kind !== "SITE" && (
                <button className="btn ghost" onClick={() => setDialog("template")}>
                  <LayoutTemplate size={15} aria-hidden />
                  Save as template
                </button>
              )}
            </>
          ) : undefined
        }
        tabs={<Tabs tabs={tabs} active={tab} onSelect={(t) => setParams({ tab: t })} />}
      />
      <div className="content">
        {tab === "objects" && holdsObjects && <ObjectsTab context={context} />}
        {tab === "contexts" && <ContextsTab context={context} />}
        {tab === "team" && <TeamTab context={context} canManage={!!permissions?.has("MANAGE")} />}
        {tab === "preferences" && permissions?.has("MANAGE") && <PreferencesTab contextId={context.id} />}
        {tab === "access" && permissions?.has("MANAGE") && (
          // Rules on the site apply everywhere: only site admins change them.
          <AccessTab context={context} canChange={context.kind === "SITE" ? !!me?.siteAdmin : true} />
        )}
      </div>
      <NewObjectDialog context={context} open={dialog === "object"} onClose={() => setDialog(null)} />
      <NewContextDialog parent={context} kinds={childKinds} open={dialog === "context"} onClose={() => setDialog(null)} />
      {dialog === "template" && <SaveAsTemplateDialog context={context} onClose={() => setDialog(null)} />}
    </>
  );
}

// ---------- Objects ----------

function ObjectsTab({ context }: { context: Context }) {
  const { data: prefs } = useEffectivePreferences(context.id);
  const objects = useObjects(context.id, (prefs?.get("ui.tablePageSize") as number | undefined) ?? 50);
  const [filter, setFilter] = useState("");
  const rows = useMemo(() => objects.data?.pages.flat() ?? [], [objects.data]);
  const shown = useMemo(() => {
    const f = filter.trim().toLowerCase();
    return f ? rows.filter((o) => o.number.toLowerCase().includes(f) || o.name.toLowerCase().includes(f)) : rows;
  }, [rows, filter]);

  return (
    <Panel
      title="Objects"
      note={objects.data ? `${rows.length}${objects.hasNextPage ? "+" : ""}` : undefined}
      flush
      actions={
        <input
          className="filter"
          type="search"
          aria-label="Filter objects"
          placeholder="Filter by number or name"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
        />
      }
    >
      <ErrorBanner error={objects.error} />
      {objects.isLoading ? (
        <Loading />
      ) : rows.length === 0 ? (
        <p className="empty">No objects yet.</p>
      ) : (
        <ObjectTable
          objects={shown}
          footer={
            <>
              <span>
                {shown.length === rows.length ? `${rows.length} objects` : `${shown.length} of ${rows.length} objects`}
              </span>
              {objects.hasNextPage && (
                <button className="btn small" onClick={() => void objects.fetchNextPage()} disabled={objects.isFetchingNextPage}>
                  Load more
                </button>
              )}
            </>
          }
        />
      )}
    </Panel>
  );
}

function NewObjectDialog({ context, open, onClose }: { context: Context; open: boolean; onClose: () => void }) {
  const { data: types } = useTypes();
  const create = useCreateObject();
  const navigate = useNavigate();
  const [typeId, setTypeId] = useState("");
  const [name, setName] = useState("");
  const [number, setNumber] = useState("");
  const chosenType = typeId || types?.[0]?.id;
  const { data: definitions } = useTypeAttributes(chosenType);
  const { data: layout } = useLayout(chosenType, "CREATE");
  // Edits belong to the type they were made for; switching type starts again from its defaults.
  const [edits, setEdits] = useState<{ typeId: string; values: Attributes }>();
  const values = edits && edits.typeId === chosenType ? edits.values : defaultValues(definitions ?? []);
  const formId = "new-object-form";

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!chosenType) return;
    create.mutate(
      { typeId: chosenType, contextId: context.id, name, number: number.trim() || null, attributes: values },
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
      open={open}
      title={`New object in ${context.name}`}
      onClose={onClose}
      footer={
        <>
          <button className="btn" type="button" onClick={onClose}>
            Cancel
          </button>
          <button className="btn primary" type="submit" form={formId} disabled={create.isPending || !name.trim()}>
            Create
          </button>
        </>
      }
    >
      <form id={formId} onSubmit={submit} noValidate style={{ display: "grid", gap: 12 }}>
        <ErrorBanner error={create.error} />
        <div className="section-label">Identity</div>
        <div className="attr-form">
          <div className="field">
            <label htmlFor="obj-type">Type</label>
            <select id="obj-type" value={chosenType ?? ""} onChange={(e) => setTypeId(e.target.value)}>
              {types?.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.displayName}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor="obj-number">Number</label>
            <input id="obj-number" value={number} onChange={(e) => setNumber(e.target.value)} placeholder="Assigned automatically" />
          </div>
          <div className="field wide">
            <div className="label-row">
              <label htmlFor="obj-name">Name</label>
              <span aria-hidden className="required">*</span>
            </div>
            <input id="obj-name" value={name} onChange={(e) => setName(e.target.value)} required autoFocus />
          </div>
        </div>
        {definitions && layout && chosenType && (
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

// ---------- Contexts ----------

function ContextsTab({ context }: { context: Context }) {
  const { data: children, isLoading, error } = useChildren(context.id);

  return (
    <Panel title="Contexts" note={children ? String(children.length) : undefined} flush>
      <ErrorBanner error={error} />
      {isLoading ? (
        <Loading />
      ) : !children?.length ? (
        <p className="empty">Nothing here that you can see.</p>
      ) : (
        <div className="grid-wrap">
          <table className="grid" aria-label="Contexts">
            <thead>
              <tr>
                <th className="icon-cell"><span className="sr-only">Icon</span></th>
                <th>Name</th>
                <th>Kind</th>
                <th>Created by</th>
                <th>Created</th>
              </tr>
            </thead>
            <tbody>
              {children.map((c) => (
                <tr key={c.id}>
                  <td className="icon-cell">
                    <ContextIcon kind={c.kind} />
                  </td>
                  <td>
                    <Link className="num-link" to={`/contexts/${c.id}`}>
                      {c.name}
                    </Link>
                  </td>
                  <td className="nowrap">{kindLabel(c.kind)}</td>
                  <td className="nowrap">
                    <PrincipalName id={c.createdBy} />
                  </td>
                  <td className="nowrap muted">{formatDate(c.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Panel>
  );
}

function NewContextDialog({
  parent,
  kinds,
  open,
  onClose,
}: {
  parent: Context;
  kinds: ContextKind[];
  open: boolean;
  onClose: () => void;
}) {
  const create = useCreateContext();
  const navigate = useNavigate();
  const [kind, setKind] = useState<ContextKind | "">("");
  const [name, setName] = useState("");
  const [templateId, setTemplateId] = useState("");
  const formId = "new-context-form";
  const chosenKind = kind || kinds[0];
  const { data: templates } = useContextTemplates(chosenKind);
  const template = templates?.find((t) => t.id === templateId);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!chosenKind) return;
    create.mutate(
      { parentId: parent.id, kind: chosenKind, name, templateId: template?.id ?? null },
      {
        onSuccess: (ctx) => {
          onClose();
          navigate(`/contexts/${ctx.id}`);
        },
      },
    );
  };

  return (
    <Dialog
      open={open}
      title={`New context in ${parent.name}`}
      onClose={onClose}
      footer={
        <>
          <button className="btn" type="button" onClick={onClose}>
            Cancel
          </button>
          <button className="btn primary" type="submit" form={formId} disabled={create.isPending || !name.trim()}>
            Create
          </button>
        </>
      }
    >
      <form id={formId} onSubmit={submit} className="attr-form">
        <ErrorBanner error={create.error} />
        {kinds.length > 1 && (
          <div className="field">
            <label htmlFor="ctx-kind">Kind</label>
            <select
              id="ctx-kind"
              value={chosenKind}
              onChange={(e) => {
                setKind(e.target.value as ContextKind);
                setTemplateId("");
              }}
            >
              {kinds.map((k) => (
                <option key={k} value={k}>
                  {kindLabel(k)}
                </option>
              ))}
            </select>
          </div>
        )}
        <div className="field">
          <label htmlFor="ctx-name">Name</label>
          <input id="ctx-name" value={name} onChange={(e) => setName(e.target.value)} required autoFocus />
          <span className="hint">You will become its manager.</span>
        </div>
        {!!templates?.length && (
          <div className="field wide">
            <label htmlFor="ctx-template">Template</label>
            <select id="ctx-template" value={templateId} onChange={(e) => setTemplateId(e.target.value)}>
              <option value="">None: start empty</option>
              {templates.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.name}
                </option>
              ))}
            </select>
            {template && (
              <span className="hint">
                {template.description ? `${template.description} · ` : ""}
                {template.content.policies.length} access rule{template.content.policies.length === 1 ? "" : "s"},{" "}
                {template.content.members.length} member{template.content.members.length === 1 ? "" : "s"},{" "}
                {template.content.sharedTeams.length} shared team{template.content.sharedTeams.length === 1 ? "" : "s"}
              </span>
            )}
          </div>
        )}
      </form>
    </Dialog>
  );
}

function SaveAsTemplateDialog({ context, onClose }: { context: Context; onClose: () => void }) {
  const save = useSaveAsTemplate(context.id);
  const [name, setName] = useState(`${context.name} template`);
  const [description, setDescription] = useState("");
  const [saved, setSaved] = useState(false);
  return (
    <Dialog
      open
      title={`Save ${context.name} as a template`}
      onClose={onClose}
      footer={
        saved ? (
          <button className="btn primary" onClick={onClose}>
            Done
          </button>
        ) : (
          <>
            <button className="btn" type="button" onClick={onClose}>
              Cancel
            </button>
            <button className="btn primary" type="submit" form="template-form" disabled={save.isPending || !name.trim()}>
              Save template
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
          save.mutate({ name: name.trim(), description: description.trim() || null }, { onSuccess: () => setSaved(true) });
        }}
      >
        <ErrorBanner error={save.error} />
        {saved ? (
          <p style={{ margin: 0 }}>
            Saved. New {kindLabel(context.kind).toLowerCase()}s can now start from <strong>{name}</strong>.
          </p>
        ) : (
          <>
            <p className="muted" style={{ margin: 0 }}>
              Captures this {kindLabel(context.kind).toLowerCase()}'s own access rules, team members and attached shared teams.
              Its objects are not copied.
            </p>
            <div className="field">
              <label htmlFor="template-name">Template name</label>
              <input id="template-name" value={name} onChange={(e) => setName(e.target.value)} autoFocus />
            </div>
            <div className="field">
              <label htmlFor="template-description">Description</label>
              <input id="template-description" value={description} onChange={(e) => setDescription(e.target.value)} />
            </div>
          </>
        )}
      </form>
    </Dialog>
  );
}
