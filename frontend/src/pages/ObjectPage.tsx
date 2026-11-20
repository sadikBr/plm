import { Copy, GitBranchPlus, Link2, Lock, LockOpen, MoreHorizontal, Pencil, Undo2, Upload } from "lucide-react";
import { useState } from "react";
import { Link, useSearchParams } from "react-router";
import { isApiError } from "../api/client";
import {
  useAudit,
  useContextById,
  useHistory,
  useMe,
  useObject,
  useObjectActions,
  usePermissions,
  useTypeAttributes,
  useWorkingCopy,
  type AttributeDefinition,
  type Attributes,
  type VersionedObject,
} from "../api/queries";
import { AttributeForm, AttributeValues, fieldErrors } from "../components/AttributeForm";
import {
  Dialog,
  ErrorBanner,
  Loading,
  MenuButton,
  Panel,
  PrincipalName,
  Props,
  StateBadge,
  TypeIcon,
  formatDate,
  useTypeName,
} from "../components/common";
import { Breadcrumbs, PageHeader, Tabs, useIdParam } from "../components/Layout";
import { NotFoundPage } from "./NotFoundPage";

type Tab = "details" | "history" | "audit";

/** Remounts per object, so page state never leaks between objects. */
export function ObjectPage() {
  const id = useIdParam();
  return <ObjectView key={id} id={id} />;
}

function ObjectView({ id }: { id: string }) {
  const { data: obj, error, isLoading } = useObject(id);
  const { data: me } = useMe();
  const [params, setParams] = useSearchParams();

  if (isLoading) return <Loading />;
  if (isApiError(error, 404)) return <NotFoundPage what="object" />;
  if (error || !obj) return <ErrorBanner error={error} />;

  const tab = (params.get("tab") as Tab | null) ?? "details";
  const tabs: [Tab, string][] = [
    ["details", "Details"],
    ["history", "History"],
    ...(me?.siteAdmin ? [["audit", "Audit"] as [Tab, string]] : []),
  ];
  const myId = me?.user.id;

  return (
    <>
      <ObjectHeader obj={obj} myId={myId} tabs={<Tabs tabs={tabs} active={tab} onSelect={(t) => setParams({ tab: t })} />} />
      <div className="content">
        {tab === "details" && <DetailsTab obj={obj} myId={myId} />}
        {tab === "history" && <HistoryTab id={obj.id} />}
        {tab === "audit" && me?.siteAdmin && <AuditTab id={obj.id} />}
      </div>
    </>
  );
}

function ObjectHeader({ obj, myId, tabs }: { obj: VersionedObject; myId?: string; tabs: React.ReactNode }) {
  const { data: permissions } = usePermissions(obj.contextId, obj.typeId);
  const typeName = useTypeName(obj.typeId);
  const actions = useObjectActions(obj.id);
  const [checkingIn, setCheckingIn] = useState(false);
  const [comment, setComment] = useState("");

  const mine = !!myId && obj.checkedOutBy === myId;
  const free = !obj.checkedOutBy;
  const canModify = !!permissions?.has("MODIFY");
  const canRevise = !!permissions?.has("REVISE");
  const busy = Object.values(actions).some((m) => m.isPending);
  const failed = Object.values(actions).find((m) => m.error)?.error;

  return (
    <>
      <PageHeader
        crumbs={<Breadcrumbs contextId={obj.contextId} current={obj.number} />}
        icon={<TypeIcon typeId={obj.typeId} size={18} />}
        title={
          <>
            <span className="num">{obj.number}</span>
            <span className="sep">·</span>
            <span className="nm">{obj.name}</span>
          </>
        }
        meta={
          <>
            <span className="tag accent version" data-testid="version">
              {obj.version}
            </span>
            <StateBadge state={obj.latest.state} />
            {obj.checkedOutBy && (
              <span className="tag warn" data-testid="checked-out">
                <Lock size={11} aria-hidden />
                Checked out by {mine ? "you" : <PrincipalName id={obj.checkedOutBy} />}
              </span>
            )}
            <span className="sub">{typeName}</span>
          </>
        }
        toolbar={
          <>
            <button className="btn ghost" disabled={busy || !free || !canModify} onClick={() => actions.checkOut.mutate()}>
              <LockOpen size={15} aria-hidden />
              Check out
            </button>
            <button className="btn ghost" disabled={busy || !mine} onClick={() => setCheckingIn(true)}>
              <Upload size={15} aria-hidden />
              Check in
            </button>
            <button className="btn ghost" disabled={busy || !mine} onClick={() => actions.undoCheckOut.mutate()}>
              <Undo2 size={15} aria-hidden />
              Undo check-out
            </button>
            <span className="divider" />
            <button className="btn ghost" disabled={busy || !free || !canRevise} onClick={() => actions.revise.mutate()}>
              <GitBranchPlus size={15} aria-hidden />
              Revise
            </button>
            <span className="grow" />
            <MenuButton label="More" icon={<MoreHorizontal size={15} aria-hidden />}>
              {(close) => (
                <>
                  <button
                    role="menuitem"
                    onClick={() => {
                      void navigator.clipboard?.writeText(obj.number);
                      close();
                    }}
                  >
                    <Copy size={14} aria-hidden /> Copy number
                  </button>
                  <button
                    role="menuitem"
                    onClick={() => {
                      void navigator.clipboard?.writeText(window.location.href);
                      close();
                    }}
                  >
                    <Link2 size={14} aria-hidden /> Copy link
                  </button>
                </>
              )}
            </MenuButton>
          </>
        }
        tabs={tabs}
      />
      {failed != null && (
        <div style={{ padding: "12px 16px 0" }}>
          <ErrorBanner error={failed} />
        </div>
      )}
      <Dialog
        open={checkingIn}
        title={`Check in ${obj.number}`}
        onClose={() => setCheckingIn(false)}
        footer={
          <>
            <button className="btn" onClick={() => setCheckingIn(false)}>
              Cancel
            </button>
            <button
              className="btn primary"
              disabled={busy}
              onClick={() =>
                actions.checkIn.mutate(comment, {
                  onSuccess: () => {
                    setCheckingIn(false);
                    setComment("");
                  },
                })
              }
            >
              Check in
            </button>
          </>
        }
      >
        <div className="field">
          <label htmlFor="checkin-comment">Comment</label>
          <textarea
            id="checkin-comment"
            value={comment}
            onChange={(e) => setComment(e.target.value)}
            placeholder="What changed?"
            autoFocus
          />
        </div>
      </Dialog>
    </>
  );
}

function DetailsTab({ obj, myId }: { obj: VersionedObject; myId?: string }) {
  const mine = !!myId && obj.checkedOutBy === myId;
  const workingCopy = useWorkingCopy(obj.id, mine);
  const { data: definitions } = useTypeAttributes(obj.typeId);
  const { data: context } = useContextById(obj.contextId);
  const typeName = useTypeName(obj.typeId);
  const latest = obj.latest;

  return (
    <div className="cols">
      {mine ? (
        workingCopy.data && definitions ? (
          <WorkingCopyEditor
            key={workingCopy.data.id}
            objectId={obj.id}
            version={workingCopy.data.version}
            definitions={definitions}
            initial={workingCopy.data.attributes as Attributes}
          />
        ) : (
          <Panel title="Working copy">
            <ErrorBanner error={workingCopy.error} />
            <Loading />
          </Panel>
        )
      ) : (
        <Panel title="Attributes" note={`as of ${latest.version}`}>
          {definitions ? <AttributeValues definitions={definitions} values={latest.attributes as Attributes} /> : <Loading />}
        </Panel>
      )}
      <Panel title="General">
        <Props
          items={[
            ["Number", obj.number],
            ["Name", obj.name],
            ["Type", typeName],
            ["Version", <span className="version">{latest.version}</span>],
            ["State", <StateBadge state={latest.state} />],
            ["Context", context ? <Link to={`/contexts/${obj.contextId}`}>{context.name}</Link> : null],
            ["Checked out by", obj.checkedOutBy ? <PrincipalName id={obj.checkedOutBy} /> : null],
            ["Last checked in", <>{formatDate(latest.checkedInAt)} · <PrincipalName id={latest.createdBy} /></>],
            ["Comment", latest.comment],
            ["Created", <>{formatDate(obj.createdAt)} · <PrincipalName id={obj.createdBy} /></>],
          ]}
        />
      </Panel>
    </div>
  );
}

function WorkingCopyEditor({
  objectId,
  version,
  definitions,
  initial,
}: {
  objectId: string;
  version: string;
  definitions: AttributeDefinition[];
  initial: Attributes;
}) {
  const { updateAttributes } = useObjectActions(objectId);
  const [values, setValues] = useState(initial);
  const [dirty, setDirty] = useState(false);
  const errors = fieldErrors(updateAttributes.error);

  return (
    <form
      className="panel"
      noValidate
      onSubmit={(e) => {
        e.preventDefault();
        updateAttributes.mutate(values, { onSuccess: () => setDirty(false) });
      }}
    >
      <div className="panel-head">
        <Pencil size={13} className="icon-muted" aria-hidden />
        <h2>
          Working copy <span className="version">{version}</span>
        </h2>
        <span className="note">Only you see these edits until you check in.</span>
      </div>
      <div className="panel-body" style={{ display: "grid", gap: 10 }}>
        {updateAttributes.error != null && Object.keys(errors).length === 0 && <ErrorBanner error={updateAttributes.error} />}
        <AttributeForm
          idPrefix="wc"
          definitions={definitions}
          values={values}
          onChange={(next) => {
            setValues(next);
            setDirty(true);
          }}
          errors={errors}
        />
      </div>
      <div className="panel-foot">
        <button className="btn primary" type="submit" disabled={updateAttributes.isPending || !dirty}>
          Save attributes
        </button>
        {dirty ? <span className="muted">Unsaved changes</span> : updateAttributes.isSuccess && <span className="muted">Saved.</span>}
      </div>
    </form>
  );
}

function HistoryTab({ id }: { id: string }) {
  const { data, isLoading, error } = useHistory(id);
  return (
    <Panel title="Iterations" note={data ? String(data.length) : undefined} flush>
      <ErrorBanner error={error} />
      {isLoading ? (
        <Loading />
      ) : (
        <div className="grid-wrap">
          <table className="grid" aria-label="Iteration history">
            <thead>
              <tr>
                <th>Version</th>
                <th>State</th>
                <th>Comment</th>
                <th>Checked in by</th>
                <th>Checked in</th>
              </tr>
            </thead>
            <tbody>
              {data?.map((it) => (
                <tr key={it.id}>
                  <td className="nowrap version">{it.version}</td>
                  <td>
                    <StateBadge state={it.state} />
                  </td>
                  <td>{it.comment ?? <span className="muted">—</span>}</td>
                  <td className="nowrap">
                    <PrincipalName id={it.createdBy} />
                  </td>
                  <td className="nowrap muted">{formatDate(it.checkedInAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Panel>
  );
}

function AuditTab({ id }: { id: string }) {
  const { data, isLoading, error } = useAudit(id, true);
  return (
    <Panel title="Audit trail" note={data ? String(data.length) : undefined} flush>
      <ErrorBanner error={error} />
      {isLoading ? (
        <Loading />
      ) : (
        <div className="grid-wrap">
          <table className="grid" aria-label="Audit trail">
            <thead>
              <tr>
                <th>When</th>
                <th>Action</th>
                <th>Who</th>
                <th>Details</th>
              </tr>
            </thead>
            <tbody>
              {data?.map((entry) => (
                <tr key={entry.seq}>
                  <td className="nowrap muted">{formatDate(entry.occurredAt)}</td>
                  <td className="nowrap">{auditAction(entry.action)}</td>
                  <td className="nowrap">
                    <PrincipalName id={entry.actor} />
                  </td>
                  <td>
                    {"version" in entry.details && <span className="version">{String(entry.details.version)}</span>}
                    {"comment" in entry.details && entry.details.comment ? ` · “${String(entry.details.comment)}”` : ""}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Panel>
  );
}

/** `versioning.ObjectCheckedIn.v1` → `Checked in`. */
function auditAction(action: string): string {
  const name = action.replace(/^\w+\./, "").replace(/\.v\d+$/, "").replace(/^Object/, "");
  const words = name.replace(/([a-z])([A-Z])/g, "$1 $2").toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}
