import { useState } from "react";
import { Link, useSearchParams } from "react-router";
import { isApiError } from "../api/client";
import {
  useAudit,
  useHistory,
  useMe,
  useObject,
  useObjectActions,
  usePermissions,
  useTypes,
  useWorkingCopy,
  type VersionedObject,
} from "../api/queries";
import { AttributesEditor, AttributesView } from "../components/AttributesEditor";
import { Dialog, ErrorBanner, Loading, PrincipalName, formatDate } from "../components/common";
import { Breadcrumbs, useIdParam } from "../components/Layout";
import { NotFoundPage } from "./NotFoundPage";

type Tab = "details" | "history" | "audit";

export function ObjectPage() {
  const id = useIdParam();
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

  return (
    <>
      <Breadcrumbs contextId={obj.contextId} current={obj.number} />
      <ObjectHeader obj={obj} myId={me?.user.id} />
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
      {tab === "details" && <DetailsTab obj={obj} myId={me?.user.id} />}
      {tab === "history" && <HistoryTab id={obj.id} />}
      {tab === "audit" && me?.siteAdmin && <AuditTab id={obj.id} />}
    </>
  );
}

function ObjectHeader({ obj, myId }: { obj: VersionedObject; myId?: string }) {
  const { data: permissions } = usePermissions(obj.contextId, obj.typeId);
  const { data: types } = useTypes();
  const actions = useObjectActions(obj.id);
  const [checkingIn, setCheckingIn] = useState(false);
  const [comment, setComment] = useState("");

  const mine = !!myId && obj.checkedOutBy === myId;
  const free = !obj.checkedOutBy;
  const canModify = !!permissions?.has("MODIFY");
  const canRevise = !!permissions?.has("REVISE");
  const typeName = types?.find((t) => t.id === obj.typeId)?.displayName ?? "Object";
  const busy = Object.values(actions).some((m) => m.isPending);
  const failed = Object.values(actions).find((m) => m.error)?.error;

  return (
    <>
      <div className="page-header">
        <div className="titles">
          <div className="row" style={{ flexWrap: "wrap" }}>
            <h1>
              <span className="mono">{obj.number}</span> · {obj.name}
            </h1>
            <span className="badge accent version" data-testid="version">
              {obj.version}
            </span>
            <span className="badge">{obj.latest.state}</span>
            {obj.checkedOutBy && (
              <span className="badge warn" data-testid="checked-out">
                Checked out by {mine ? "you" : <PrincipalName id={obj.checkedOutBy} />}
              </span>
            )}
          </div>
          <div className="subtitle">{typeName}</div>
        </div>
        <div className="actions">
          {free && canModify && (
            <button className="btn primary" disabled={busy} onClick={() => actions.checkOut.mutate()}>
              Check out
            </button>
          )}
          {mine && (
            <>
              <button className="btn primary" disabled={busy} onClick={() => setCheckingIn(true)}>
                Check in
              </button>
              <button className="btn" disabled={busy} onClick={() => actions.undoCheckOut.mutate()}>
                Undo check-out
              </button>
            </>
          )}
          {free && canRevise && (
            <button className="btn" disabled={busy} onClick={() => actions.revise.mutate()}>
              Revise
            </button>
          )}
        </div>
      </div>
      <ErrorBanner error={failed} />
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
  const actions = useObjectActions(obj.id);
  const latest = obj.latest;

  return (
    <div className="grid-2">
      {mine ? (
        <div className="card">
          <h2>
            Working copy <span className="version">{workingCopy.data?.version}</span>
          </h2>
          <p className="muted" style={{ marginTop: -6 }}>
            Only you see these edits until you check in.
          </p>
          <ErrorBanner error={workingCopy.error} />
          {workingCopy.data && (
            <AttributesEditor
              key={workingCopy.data.id}
              initial={workingCopy.data.attributes as Record<string, unknown>}
              saving={actions.updateAttributes.isPending}
              onSave={(attributes) => actions.updateAttributes.mutate(attributes)}
            />
          )}
          {actions.updateAttributes.isSuccess && <p className="muted">Saved.</p>}
        </div>
      ) : (
        <div className="card">
          <h2>Attributes</h2>
          <AttributesView attributes={latest.attributes as Record<string, unknown>} />
        </div>
      )}
      <div className="card">
        <h2>
          Latest iteration <span className="version">{latest.version}</span>
        </h2>
        <dl className="props">
          <dt>Number</dt>
          <dd className="mono">{obj.number}</dd>
          <dt>State</dt>
          <dd>{latest.state}</dd>
          <dt>Checked in</dt>
          <dd>
            {formatDate(latest.checkedInAt)} by <PrincipalName id={latest.createdBy} />
          </dd>
          <dt>Comment</dt>
          <dd>{latest.comment ?? <span className="muted">—</span>}</dd>
          <dt>Created</dt>
          <dd>
            {formatDate(obj.createdAt)} by <PrincipalName id={obj.createdBy} />
          </dd>
          <dt>Context</dt>
          <dd>
            <Link to={`/contexts/${obj.contextId}`}>Open</Link>
          </dd>
        </dl>
      </div>
    </div>
  );
}

function HistoryTab({ id }: { id: string }) {
  const { data, isLoading, error } = useHistory(id);
  if (isLoading) return <Loading />;
  return (
    <div className="card">
      <ErrorBanner error={error} />
      <div className="table-wrap">
        <table aria-label="Iteration history">
          <thead>
            <tr>
              <th>Version</th>
              <th>State</th>
              <th>Comment</th>
              <th>By</th>
              <th>Checked in</th>
            </tr>
          </thead>
          <tbody>
            {data?.map((it) => (
              <tr key={it.id}>
                <td className="version">{it.version}</td>
                <td>
                  <span className="badge">{it.state}</span>
                </td>
                <td>{it.comment ?? <span className="muted">—</span>}</td>
                <td>
                  <PrincipalName id={it.createdBy} />
                </td>
                <td className="muted">{formatDate(it.checkedInAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function AuditTab({ id }: { id: string }) {
  const { data, isLoading, error } = useAudit(id, true);
  if (isLoading) return <Loading />;
  return (
    <div className="card">
      <ErrorBanner error={error} />
      <table aria-label="Audit trail">
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
              <td className="muted">{formatDate(entry.occurredAt)}</td>
              <td>{entry.action.replace(/^versioning\./, "").replace(/\.v\d+$/, "")}</td>
              <td>
                <PrincipalName id={entry.actor} />
              </td>
              <td className="mono" style={{ maxWidth: 420, overflowWrap: "anywhere" }}>
                {"version" in entry.details ? String(entry.details.version) : ""}
                {"comment" in entry.details && entry.details.comment ? ` · “${String(entry.details.comment)}”` : ""}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
