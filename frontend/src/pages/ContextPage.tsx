import { useState, type FormEvent } from "react";
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
  useTypeAttributes,
  useTypes,
  type Attributes,
  type Context,
  type ContextKind,
  type TeamRole,
} from "../api/queries";
import { AttributeForm, defaultValues, fieldErrors } from "../components/AttributeForm";
import {
  Dialog,
  ErrorBanner,
  KindBadge,
  Loading,
  PrincipalName,
  formatDate,
  kindLabel,
} from "../components/common";
import { Breadcrumbs, useIdParam } from "../components/Layout";
import { NotFoundPage } from "./NotFoundPage";

type Tab = "objects" | "contexts" | "team";

export function ContextPage() {
  const id = useIdParam();
  const { data: context, error, isLoading } = useContextById(id);
  const [params, setParams] = useSearchParams();

  if (isLoading) return <Loading />;
  if (isApiError(error, 404)) return <NotFoundPage what="context" />;
  if (error || !context) return <ErrorBanner error={error} />;

  const holdsObjects = HOLDS_OBJECTS.has(context.kind);
  const tab = (params.get("tab") as Tab | null) ?? (holdsObjects ? "objects" : "contexts");
  const tabs: [Tab, string][] = [
    ...(holdsObjects ? [["objects", "Objects"] as [Tab, string]] : []),
    ...(ALLOWED_CHILDREN[context.kind].length > 0 ? [["contexts", "Contexts"] as [Tab, string]] : []),
    ["team", "Team"],
  ];

  return (
    <>
      {context.parentId && <Breadcrumbs contextId={context.parentId} current={context.name} />}
      <div className="page-header">
        <div className="titles">
          <div className="row">
            <h1>{context.name}</h1>
            <KindBadge kind={context.kind} />
          </div>
          <div className="subtitle">
            Created {formatDate(context.createdAt)} by <PrincipalName id={context.createdBy} />
          </div>
        </div>
      </div>
      <nav className="tabs" aria-label="Sections">
        {tabs.map(([key, label]) => (
          <a
            key={key}
            href={`?tab=${key}`}
            className={tab === key ? "active" : undefined}
            aria-current={tab === key ? "page" : undefined}
            onClick={(e) => {
              e.preventDefault();
              setParams({ tab: key });
            }}
          >
            {label}
          </a>
        ))}
      </nav>
      {tab === "objects" && holdsObjects && <ObjectsTab context={context} />}
      {tab === "contexts" && <ContextsTab context={context} />}
      {tab === "team" && <TeamTab context={context} />}
    </>
  );
}

// ---------- Objects ----------

function ObjectsTab({ context }: { context: Context }) {
  const objects = useObjects(context.id);
  const { data: permissions } = usePermissions(context.id);
  const [creating, setCreating] = useState(false);
  const rows = objects.data?.pages.flat() ?? [];

  return (
    <div className="card">
      <div className="row" style={{ justifyContent: "space-between", marginBottom: 8 }}>
        <h2 style={{ margin: 0 }}>Objects</h2>
        {permissions?.has("CREATE") && (
          <button className="btn primary" onClick={() => setCreating(true)}>
            New object
          </button>
        )}
      </div>
      <ErrorBanner error={objects.error} />
      {objects.isLoading ? (
        <Loading />
      ) : rows.length === 0 ? (
        <p className="empty">No objects yet.</p>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Number</th>
                <th>Name</th>
                <th>Version</th>
                <th>State</th>
                <th>Checked out by</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((o) => (
                <tr key={o.id}>
                  <td className="mono">
                    <Link to={`/objects/${o.id}`}>{o.number}</Link>
                  </td>
                  <td>
                    <Link to={`/objects/${o.id}`}>{o.name}</Link>
                  </td>
                  <td className="version">{o.version}</td>
                  <td>
                    <span className="badge">{o.latest.state}</span>
                  </td>
                  <td>{o.checkedOutBy ? <PrincipalName id={o.checkedOutBy} /> : <span className="muted">—</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {objects.hasNextPage && (
        <button className="btn" onClick={() => void objects.fetchNextPage()} disabled={objects.isFetchingNextPage}>
          Load more
        </button>
      )}
      <NewObjectDialog context={context} open={creating} onClose={() => setCreating(false)} />
    </div>
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
  // Edits belong to the type they were made for; switching type starts again from its defaults.
  const [edits, setEdits] = useState<{ typeId: string; values: Attributes }>();
  const values = edits && edits.typeId === chosenType ? edits.values : defaultValues(definitions ?? []);
  const formId = "new-object-form";

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!chosenType) return;
    create.mutate(
      { typeId: chosenType, contextId: context.id, name, number: number.trim() || null, attributes: values },
      { onSuccess: (obj) => navigate(`/objects/${obj.id}`) },
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
      <form id={formId} onSubmit={submit} noValidate>
        <ErrorBanner error={create.error} />
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
          <div className="field" style={{ gridColumn: "1 / -1" }}>
            <div className="label-row">
              <label htmlFor="obj-name">Name</label>
              <span aria-hidden className="required">*</span>
            </div>
            <input id="obj-name" value={name} onChange={(e) => setName(e.target.value)} required autoFocus />
          </div>
        </div>
        {definitions && chosenType && (
          <AttributeForm
            idPrefix="new"
            definitions={definitions}
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
  const { data: permissions } = usePermissions(context.id);
  const { data: me } = useMe();
  const allowed = ALLOWED_CHILDREN[context.kind];
  // Organizations are created by site admins only; everything else needs MANAGE on the parent.
  const canCreate = context.kind === "SITE" ? !!me?.siteAdmin : !!permissions?.has("MANAGE");
  const [creating, setCreating] = useState(false);

  return (
    <div className="card">
      <div className="row" style={{ justifyContent: "space-between", marginBottom: 8 }}>
        <h2 style={{ margin: 0 }}>Contexts</h2>
        {canCreate && allowed.length > 0 && (
          <button className="btn primary" onClick={() => setCreating(true)}>
            New {allowed.length === 1 ? kindLabel(allowed[0]!).toLowerCase() : "context"}
          </button>
        )}
      </div>
      <ErrorBanner error={error} />
      {isLoading ? (
        <Loading />
      ) : !children?.length ? (
        <p className="empty">Nothing here yet{canCreate ? "." : " that you can see."}</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Name</th>
              <th>Kind</th>
              <th>Created</th>
            </tr>
          </thead>
          <tbody>
            {children.map((c) => (
              <tr key={c.id}>
                <td>
                  <Link to={`/contexts/${c.id}`}>{c.name}</Link>
                </td>
                <td>
                  <KindBadge kind={c.kind} />
                </td>
                <td className="muted">{formatDate(c.createdAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <NewContextDialog parent={context} kinds={allowed} open={creating} onClose={() => setCreating(false)} />
    </div>
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
    create.mutate({ parentId: parent.id, kind: chosenKind, name }, { onSuccess: (ctx) => navigate(`/contexts/${ctx.id}`) });
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
      <form id={formId} onSubmit={submit}>
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
  VIEWER: "can read",
  MEMBER: "can create, edit and revise",
  MANAGER: "can also manage the team and sub-contexts",
};

function TeamTab({ context }: { context: Context }) {
  const { data: members, isLoading, error } = useMembers(context.id);
  const { data: permissions } = usePermissions(context.id);
  const canManage = !!permissions?.has("MANAGE");
  const put = usePutMember(context.id);
  const remove = useRemoveMember(context.id);

  return (
    <div className="grid-2">
      <div className="card">
        <h2>Team of {context.name}</h2>
        <p className="muted" style={{ marginTop: -6 }}>
          Roles also apply to everything inside this {kindLabel(context.kind).toLowerCase()}.
        </p>
        <ErrorBanner error={error ?? put.error ?? remove.error} />
        {isLoading ? (
          <Loading />
        ) : !members?.length ? (
          <p className="empty">No direct members. Access may be inherited from a parent context.</p>
        ) : (
          <table>
            <thead>
              <tr>
                <th>User</th>
                <th id="team-role-col">Role</th>
                {canManage && <th aria-label="Actions" />}
              </tr>
            </thead>
            <tbody>
              {members.map((m) => (
                <tr key={m.principalId}>
                  <td id={`member-${m.principalId}`}>
                    <PrincipalName id={m.principalId} />
                  </td>
                  <td>
                    {canManage ? (
                      <select
                        // Read as e.g. "Bob Builder Role": which member this changes.
                        aria-labelledby={`member-${m.principalId} team-role-col`}
                        value={m.role}
                        onChange={(e) => put.mutate({ principalId: m.principalId, role: e.target.value as TeamRole })}
                      >
                        {ROLES.map((r) => (
                          <option key={r} value={r}>
                            {r}
                          </option>
                        ))}
                      </select>
                    ) : (
                      <span className="badge">{m.role}</span>
                    )}
                  </td>
                  {canManage && (
                    <td>
                      <button className="btn small danger" onClick={() => remove.mutate(m.principalId)}>
                        Remove
                      </button>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
      {canManage && <AddMemberCard contextId={context.id} />}
    </div>
  );
}

function AddMemberCard({ contextId }: { contextId: string }) {
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
    <form className="card" aria-labelledby="add-member-title" onSubmit={(e) => void submit(e)}>
      <h2 id="add-member-title">Add a member</h2>
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
              {r} — {ROLE_HELP[r]}
            </option>
          ))}
        </select>
      </div>
      <button className="btn primary" type="submit" disabled={put.isPending || !username.trim()}>
        Add
      </button>
    </form>
  );
}
