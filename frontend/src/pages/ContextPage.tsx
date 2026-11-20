import { FilePlus, FolderPlus, UserPlus } from "lucide-react";
import { useMemo, useState, type FormEvent } from "react";
import { Link, useNavigate, useSearchParams } from "react-router";
import { isApiError } from "../api/client";
import {
  ALLOWED_CHILDREN,
  HOLDS_OBJECTS,
  findPrincipalByUsername,
  useChildren,
  useContextById,
  useCreateContext,
  useCreateObject,
  useMe,
  useMembers,
  useObjects,
  usePermissions,
  usePutMember,
  useRemoveMember,
  useLayout,
  useTypeAttributes,
  useTypes,
  type Attributes,
  type Context,
  type ContextKind,
  type TeamRole,
} from "../api/queries";
import { AttributeForm, defaultValues, fieldErrors } from "../components/AttributeForm";
import {
  ContextIcon,
  Dialog,
  ErrorBanner,
  Loading,
  Panel,
  PrincipalName,
  Props,
  formatDate,
  kindLabel,
} from "../components/common";
import { Breadcrumbs, PageHeader, Tabs, useIdParam } from "../components/Layout";
import { ObjectTable } from "../components/ObjectTable";
import { NotFoundPage } from "./NotFoundPage";

type Tab = "objects" | "contexts" | "team";

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
  const [dialog, setDialog] = useState<"object" | "context" | null>(null);

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
          canCreateObject || canCreateContext ? (
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
            </>
          ) : undefined
        }
        tabs={<Tabs tabs={tabs} active={tab} onSelect={(t) => setParams({ tab: t })} />}
      />
      <div className="content">
        {tab === "objects" && holdsObjects && <ObjectsTab context={context} />}
        {tab === "contexts" && <ContextsTab context={context} />}
        {tab === "team" && <TeamTab context={context} canManage={!!permissions?.has("MANAGE")} />}
      </div>
      <NewObjectDialog context={context} open={dialog === "object"} onClose={() => setDialog(null)} />
      <NewContextDialog parent={context} kinds={childKinds} open={dialog === "context"} onClose={() => setDialog(null)} />
    </>
  );
}

// ---------- Objects ----------

function ObjectsTab({ context }: { context: Context }) {
  const objects = useObjects(context.id);
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
  const formId = "new-context-form";
  const chosenKind = kind || kinds[0];

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!chosenKind) return;
    create.mutate(
      { parentId: parent.id, kind: chosenKind, name },
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
            <select id="ctx-kind" value={chosenKind} onChange={(e) => setKind(e.target.value as ContextKind)}>
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
      </form>
    </Dialog>
  );
}

// ---------- Team ----------

const ROLES: TeamRole[] = ["VIEWER", "MEMBER", "MANAGER"];
const ROLE_HELP: Record<TeamRole, string> = {
  VIEWER: "Read",
  MEMBER: "Read, create, edit, revise",
  MANAGER: "Everything, plus team and sub-contexts",
};
const roleLabel = (r: TeamRole) => r.charAt(0) + r.slice(1).toLowerCase();

function TeamTab({ context, canManage }: { context: Context; canManage: boolean }) {
  const { data: members, isLoading, error } = useMembers(context.id);
  const put = usePutMember(context.id);
  const remove = useRemoveMember(context.id);

  return (
    <div className="cols">
      <Panel title="Team" note={`Roles also apply to everything inside this ${kindLabel(context.kind).toLowerCase()}`} flush>
        <ErrorBanner error={error ?? put.error ?? remove.error} />
        {isLoading ? (
          <Loading />
        ) : !members?.length ? (
          <p className="empty">No direct members. Access may be inherited from a parent context.</p>
        ) : (
          <div className="grid-wrap">
            <table className="grid" aria-label="Team members">
              <thead>
                <tr>
                  <th>User</th>
                  <th id="team-role-col">Role</th>
                  <th>Can</th>
                  <th>Added</th>
                  {canManage && <th aria-label="Actions" />}
                </tr>
              </thead>
              <tbody>
                {members.map((m) => (
                  <tr key={m.principalId}>
                    <td id={`member-${m.principalId}`} className="nowrap">
                      <PrincipalName id={m.principalId} />
                    </td>
                    <td style={{ width: 140 }}>
                      {canManage ? (
                        <select
                          // Read as e.g. "Bob Builder Role": which member this changes.
                          aria-labelledby={`member-${m.principalId} team-role-col`}
                          value={m.role}
                          onChange={(e) => put.mutate({ principalId: m.principalId, role: e.target.value as TeamRole })}
                        >
                          {ROLES.map((r) => (
                            <option key={r} value={r}>
                              {roleLabel(r)}
                            </option>
                          ))}
                        </select>
                      ) : (
                        <span className="tag">{roleLabel(m.role)}</span>
                      )}
                    </td>
                    <td className="muted">{ROLE_HELP[m.role]}</td>
                    <td className="nowrap muted">{formatDate(m.addedAt)}</td>
                    {canManage && (
                      <td style={{ width: 80 }}>
                        <button className="btn small danger" onClick={() => remove.mutate(m.principalId)}>
                          Remove
                        </button>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Panel>
      {canManage ? <AddMemberPanel contextId={context.id} /> : <RolesPanel />}
    </div>
  );
}

function RolesPanel() {
  return (
    <Panel title="Roles">
      <Props items={ROLES.map((r) => [roleLabel(r), ROLE_HELP[r]])} />
    </Panel>
  );
}

function AddMemberPanel({ contextId }: { contextId: string }) {
  const put = usePutMember(contextId);
  const [username, setUsername] = useState("");
  const [role, setRole] = useState<TeamRole>("MEMBER");
  const [lookupError, setLookupError] = useState<unknown>();

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setLookupError(undefined);
    try {
      const principal = await findPrincipalByUsername(username.trim());
      put.mutate({ principalId: principal.id, role }, { onSuccess: () => setUsername("") });
    } catch (err) {
      setLookupError(err);
    }
  };

  return (
    <form className="panel" aria-labelledby="add-member-title" onSubmit={(e) => void submit(e)}>
      <div className="panel-head">
        <h2 id="add-member-title">Add a member</h2>
      </div>
      <div className="panel-body" style={{ display: "grid", gap: 10 }}>
        <ErrorBanner error={lookupError ?? put.error} />
        <div className="field">
          <label htmlFor="member-username">Username</label>
          <input id="member-username" value={username} onChange={(e) => setUsername(e.target.value)} required />
          <span className="hint">The user must have signed in at least once.</span>
        </div>
        <div className="field">
          <label htmlFor="member-role">Role</label>
          <select id="member-role" value={role} onChange={(e) => setRole(e.target.value as TeamRole)}>
            {ROLES.map((r) => (
              <option key={r} value={r}>
                {roleLabel(r)} — {ROLE_HELP[r].toLowerCase()}
              </option>
            ))}
          </select>
        </div>
      </div>
      <div className="panel-foot">
        <button className="btn primary" type="submit" disabled={put.isPending || !username.trim()}>
          <UserPlus size={14} aria-hidden />
          Add
        </button>
      </div>
    </form>
  );
}
