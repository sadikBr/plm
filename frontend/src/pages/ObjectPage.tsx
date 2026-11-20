import { Lock, Pencil } from "lucide-react";
import { useState } from "react";
import { Link, useSearchParams } from "react-router";
import { isApiError } from "../api/client";
import {
  useAudit,
  useAvailableActions,
  useContextById,
  useHistory,
  useLifecycle,
  useMe,
  useObject,
  useObjectActions,
  useRenameObject,
  useChangeLifecycle,
  useLifecycles,
  useEffectivePreferences,
  useLayout,
  useTypeAttributes,
  useWorkingCopy,
  type AttributeDefinition,
  type LayoutGroup,
  type Attributes,
  type ObjectLifecycle,
  type VersionedObject,
  useShows,
} from "../api/queries";
import { AttributeForm, AttributeValues, fieldErrors, pickLabel } from "../components/AttributeForm";
import {
  Dialog,
  ErrorBanner,
  Loading,
  Panel,
  PrincipalName,
  Props,
  StateBadge,
  TypeIcon,
  formatDate,
  useTypeName,
} from "../components/common";
import { Breadcrumbs, PageHeader, Tabs, useIdParam } from "../components/Layout";
import { ObjectFiles } from "../components/ObjectFiles";
import { NotFoundPage } from "./NotFoundPage";
import { useActionRunner } from "./ActionRunner";
import { ObjectActionBar, type ActionHandlers } from "./ObjectActions";

type Tab = "details" | "history" | "audit";

/** Remounts per object, so page state never leaks between objects. */
export function ObjectPage() {
  const id = useIdParam();
  return <ObjectView key={id} id={id} />;
}

function ObjectView({ id }: { id: string }) {
  const { data: obj, error, isLoading } = useObject(id);
  const { data: me } = useMe();
  const shows = useShows();
  const [params, setParams] = useSearchParams();

  if (isLoading) return <Loading />;
  if (isApiError(error, 404)) return <NotFoundPage what="object" />;
  if (error || !obj) return <ErrorBanner error={error} />;

  const tabs: [Tab, string][] = [
    ["details", "Details"],
    ...(shows("object.history") ? [["history", "History"] as [Tab, string]] : []),
    ...(me?.siteAdmin && shows("object.audit") ? [["audit", "Audit"] as [Tab, string]] : []),
  ];
  const asked = params.get("tab") as Tab | null;
  const tab: Tab = asked && tabs.some(([t]) => t === asked) ? asked : "details";
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
  const typeName = useTypeName(obj.typeId);
  const actions = useObjectActions(obj.id);
  const { data: available } = useAvailableActions(obj);
  const runner = useActionRunner(obj.id);
  const { data: lifecycle } = useLifecycle(obj.id);
  const [checkingIn, setCheckingIn] = useState(false);
  const [comment, setComment] = useState("");
  const [stateChange, setStateChange] = useState<{ to: string; override: boolean } | null>(null);
  const [renaming, setRenaming] = useState(false);
  const [changingLifecycle, setChangingLifecycle] = useState(false);
  const { data: prefs } = useEffectivePreferences(obj.contextId);
  const commentRequired = prefs?.get("versioning.checkInCommentRequired") === true;

  const mine = !!myId && obj.checkedOutBy === myId;
  const busy = Object.values(actions).some((m) => m.isPending);
  // Set state and check-in show their errors in their dialogs, where the user is looking.
  const failed = Object.entries(actions).find(([key, m]) => key !== "setState" && key !== "checkIn" && m.error)?.[1].error;
  const label = (state: string) => stateName(lifecycle, state);
  const current = label(obj.latest.state);

  // What each registry action does on this page. Which ones show, where, and whether they can be
  // used comes from the server (GET /objects/{id}/actions).
  const handlers: ActionHandlers = {
    "object.check-out": () => actions.checkOut.mutate(),
    "object.check-in": () => setCheckingIn(true),
    "object.undo-check-out": () => actions.undoCheckOut.mutate(),
    "object.revise": () => actions.revise.mutate(),
    "object.set-state": (option) => option && setStateChange({ to: option.value, override: option.override }),
    "object.copy-number": () => void navigator.clipboard?.writeText(obj.number),
    "object.copy-link": () => void navigator.clipboard?.writeText(window.location.href),
    "object.rename": () => setRenaming(true),
    "object.change-lifecycle": () => setChangingLifecycle(true),
  };

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
            <StateBadge state={obj.latest.state} label={current} />
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
          available ? (
            <ObjectActionBar actions={available} handlers={handlers} busy={busy || runner.busy} stateLabel={label} onInvoke={runner.run} />
          ) : undefined
        }
        tabs={tabs}
      />
      {failed != null && (
        <div style={{ padding: "12px 16px 0" }}>
          <ErrorBanner error={failed} />
        </div>
      )}
      {runner.ui}
      {renaming && <RenameDialog obj={obj} onClose={() => setRenaming(false)} />}
      {changingLifecycle && lifecycle && (
        <ChangeLifecycleDialog obj={obj} current={lifecycle} onClose={() => setChangingLifecycle(false)} />
      )}
      <StateChangeDialog
        objectNumber={obj.number}
        from={current}
        change={stateChange}
        label={label}
        busy={busy}
        // A refusal (a gate, missing required attributes) is shown in the dialog, where the user is looking.
        error={actions.setState.error}
        onCancel={() => {
          setStateChange(null);
          actions.setState.reset();
        }}
        onConfirm={(comment) =>
          stateChange &&
          actions.setState.mutate(
            { state: stateChange.to, comment: comment || undefined, override: stateChange.override },
            { onSuccess: () => setStateChange(null) },
          )
        }
      />
      <Dialog
        open={checkingIn}
        title={`Check in ${obj.number}`}
        onClose={() => {
          setCheckingIn(false);
          actions.checkIn.reset();
        }}
        footer={
          <>
            <button
              className="btn"
              onClick={() => {
                setCheckingIn(false);
                actions.checkIn.reset();
              }}
            >
              Cancel
            </button>
            <button
              className="btn primary"
              disabled={busy || (commentRequired && !comment.trim())}
              title={commentRequired && !comment.trim() ? "A comment is required here" : undefined}
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
        <ErrorBanner error={actions.checkIn.error} />
        <div className="field">
          <div className="label-row">
            <label htmlFor="checkin-comment">Comment</label>
            {commentRequired && (
              <span aria-hidden className="required">
                *
              </span>
            )}
          </div>
          <textarea
            id="checkin-comment"
            required={commentRequired}
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

function stateName(lifecycle: ObjectLifecycle | undefined, state: string): string {
  const labels = lifecycle?.states.find((s) => s.key === state)?.labels;
  return labels?.en ?? state.charAt(0) + state.slice(1).toLowerCase().replace(/_/g, " ");
}

function ChangeLifecycleDialog({ obj, current, onClose }: { obj: VersionedObject; current: ObjectLifecycle; onClose: () => void }) {
  const { data: lifecycles } = useLifecycles();
  const change = useChangeLifecycle(obj.id);
  // Default: the current version of the object's own lifecycle.
  const latestOfOwn = lifecycles?.find((l) => l.name === current.lifecycleName);
  const [lifecycleId, setLifecycleId] = useState("");
  const chosen = lifecycles?.find((l) => l.id === (lifecycleId || latestOfOwn?.id)) ?? lifecycles?.[0];
  const keeps = !!chosen?.states.some((s) => s.key === obj.latest.state);
  const [state, setState] = useState("");
  const target = state || (keeps ? obj.latest.state : "");
  return (
    <Dialog
      open
      title={`Change the lifecycle of ${obj.number}`}
      onClose={onClose}
      footer={
        <>
          <button className="btn" type="button" onClick={onClose}>
            Cancel
          </button>
          <button
            className="btn primary"
            type="submit"
            form="lifecycle-form"
            disabled={change.isPending || !chosen || !target || (chosen.id === current.lifecycleId && target === obj.latest.state)}
          >
            Move
          </button>
        </>
      }
    >
      <form
        id="lifecycle-form"
        style={{ display: "grid", gap: 12 }}
        noValidate
        onSubmit={(e) => {
          e.preventDefault();
          if (chosen) change.mutate({ lifecycleId: chosen.id, state: target }, { onSuccess: onClose });
        }}
      >
        <ErrorBanner error={change.error} />
        <p className="muted" style={{ margin: 0 }}>
          Now: {current.lifecycleName} version {current.lifecycleVersion}
          {!current.lifecycleLatest && " (a newer version exists)"}, {stateName(current, obj.latest.state)}. Recorded in the audit trail.
        </p>
        <div className="field">
          <label htmlFor="lc-target">Lifecycle</label>
          <select
            id="lc-target"
            value={chosen?.id ?? ""}
            onChange={(e) => {
              setLifecycleId(e.target.value);
              setState("");
            }}
          >
            {lifecycles?.map((l) => (
              <option key={l.id} value={l.id}>
                {pickLabel(l.labels, l.name)} (version {l.version})
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="lc-state">State</label>
          <select id="lc-state" value={target} onChange={(e) => setState(e.target.value)}>
            {!target && <option value="">Choose…</option>}
            {chosen?.states.map((s) => (
              <option key={s.key} value={s.key}>
                {pickLabel(s.labels, s.key)}
              </option>
            ))}
          </select>
          {!keeps && chosen && (
            <span className="hint">This lifecycle has no {stateName(current, obj.latest.state)} state: choose one.</span>
          )}
        </div>
      </form>
    </Dialog>
  );
}

function RenameDialog({ obj, onClose }: { obj: VersionedObject; onClose: () => void }) {
  const rename = useRenameObject(obj.id);
  const [number, setNumber] = useState(obj.number);
  const [name, setName] = useState(obj.name);
  const [comment, setComment] = useState("");
  const changed = number.trim() !== obj.number || name.trim() !== obj.name;
  return (
    <Dialog
      open
      title={`Rename or renumber ${obj.number}`}
      onClose={onClose}
      footer={
        <>
          <button className="btn" type="button" onClick={onClose}>
            Cancel
          </button>
          <button
            className="btn primary"
            type="submit"
            form="rename-form"
            disabled={rename.isPending || !changed || !name.trim() || !number.trim()}
          >
            Save
          </button>
        </>
      }
    >
      <form
        id="rename-form"
        style={{ display: "grid", gap: 12 }}
        noValidate
        onSubmit={(e) => {
          e.preventDefault();
          rename.mutate(
            {
              number: number.trim() !== obj.number ? number.trim() : null,
              name: name.trim() !== obj.name ? name.trim() : null,
              comment: comment.trim() || null,
            },
            { onSuccess: onClose },
          );
        }}
      >
        <ErrorBanner error={rename.error} />
        <p className="muted" style={{ margin: 0 }}>
          Applies to every version. The old number and name stay in the audit trail.
        </p>
        <div className="field">
          <label htmlFor="rename-number">Number</label>
          <input id="rename-number" value={number} onChange={(e) => setNumber(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="rename-name">Name</label>
          <input id="rename-name" value={name} onChange={(e) => setName(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="rename-comment">Reason</label>
          <input
            id="rename-comment"
            value={comment}
            onChange={(e) => setComment(e.target.value)}
            placeholder="e.g. Align with the ERP number"
          />
        </div>
      </form>
    </Dialog>
  );
}

function StateChangeDialog({
  objectNumber,
  from,
  change,
  label,
  busy,
  error,
  onCancel,
  onConfirm,
}: {
  error?: unknown;
  objectNumber: string;
  from: string;
  change: { to: string; override: boolean } | null;
  label: (state: string) => string;
  busy: boolean;
  onCancel: () => void;
  onConfirm: (comment: string) => void;
}) {
  const [comment, setComment] = useState("");
  return (
    <Dialog
      open={change !== null}
      title={`Set state of ${objectNumber}`}
      onClose={onCancel}
      footer={
        <>
          <button className="btn" onClick={onCancel}>
            Cancel
          </button>
          <button className="btn primary" disabled={busy} onClick={() => onConfirm(comment)}>
            Set state
          </button>
        </>
      }
    >
      {change && (
        <>
          <ErrorBanner error={error} />
          <p style={{ margin: 0 }}>
            {from} → <strong>{label(change.to)}</strong>
          </p>
          {change.override && (
            <div className="banner warn">This ignores the lifecycle's transitions. It is recorded in the audit trail.</div>
          )}
          <div className="field">
            <label htmlFor="state-comment">Comment</label>
            <textarea
              id="state-comment"
              value={comment}
              onChange={(e) => setComment(e.target.value)}
              placeholder="Why? e.g. approved in design review DR-12"
            />
          </div>
        </>
      )}
    </Dialog>
  );
}

function DetailsTab({ obj, myId }: { obj: VersionedObject; myId?: string }) {
  const mine = !!myId && obj.checkedOutBy === myId;
  const workingCopy = useWorkingCopy(obj.id, mine);
  const { data: definitions } = useTypeAttributes(obj.typeId);
  const { data: infoLayout } = useLayout(obj.typeId, "INFO");
  const { data: editLayout } = useLayout(obj.typeId, "EDIT");
  const { data: context } = useContextById(obj.contextId);
  const typeName = useTypeName(obj.typeId);
  const latest = obj.latest;

  return (
    <div className="details-stack">
      <div className="cols">
        {mine ? (
          workingCopy.data && definitions && editLayout ? (
            <WorkingCopyEditor
              key={workingCopy.data.id}
              objectId={obj.id}
              version={workingCopy.data.version}
              definitions={definitions}
              groups={editLayout.groups}
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
            {definitions && infoLayout ? (
              <AttributeValues definitions={definitions} groups={infoLayout.groups} values={latest.attributes as Attributes} />
            ) : (
              <Loading />
            )}
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
              [
                "Last checked in",
                <>
                  {formatDate(latest.checkedInAt)} · <PrincipalName id={latest.createdBy} />
                </>,
              ],
              ["Comment", latest.comment],
              [
                "Created",
                <>
                  {formatDate(obj.createdAt)} · <PrincipalName id={obj.createdBy} />
                </>,
              ],
            ]}
          />
        </Panel>
      </div>
      <ObjectFiles objectId={obj.id} iterationKey={mine ? `wc:${workingCopy.data?.id ?? ""}` : latest.id} editable={mine} />
    </div>
  );
}

function WorkingCopyEditor({
  objectId,
  version,
  definitions,
  groups,
  initial,
}: {
  objectId: string;
  version: string;
  definitions: AttributeDefinition[];
  groups: LayoutGroup[];
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
          groups={groups}
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
                    {"previousNumber" in entry.details &&
                      ` · was ${String(entry.details.previousNumber)} “${String(entry.details.previousName)}”, now ${String(entry.details.number)} “${String(entry.details.name)}”`}
                    {"from" in entry.details && "to" in entry.details && ` · ${String(entry.details.from)} → ${String(entry.details.to)}`}
                    {"comment" in entry.details && entry.details.comment ? ` · “${String(entry.details.comment)}”` : ""}
                    {"actionId" in entry.details && (
                      <>
                        <code>{String(entry.details.actionId)}</code> · {String(entry.details.status).toLowerCase()}
                        {entry.details.message ? ` · ${String(entry.details.message)}` : ""}
                      </>
                    )}
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
  const name = action
    .replace(/^\w+\./, "")
    .replace(/\.v\d+$/, "")
    .replace(/^Object/, "");
  const words = name.replace(/([a-z])([A-Z])/g, "$1 $2").toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}
