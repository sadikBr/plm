import { ReferencedBy } from "../components/References";
import { ReplacementsTab } from "../structure/Replacements";
import { DocumentLinksTab, LinkingPartsTab } from "../structure/DocumentLinks";
import { WhereUsedTab } from "../structure/WhereUsed";
import { StructureTab } from "../structure/Structure";
import { auditAction } from "../audit/AuditReport";
import { language, t } from "../i18n/i18n";
import { DiscussionTab, ShareButton, useComments } from "../collaboration/Collaboration";
import { FavoriteButton, SubscribeButton, useRecordVisit } from "../personal/Personal";
import { Bookmark, Lock, Pencil } from "lucide-react";
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
  usePermissions,
  useTypes,
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
import { CompareDialog, SaveAsDialog } from "../documents/DocumentDialogs";
import { useClipboard } from "../components/Clipboard";
import { FolderLocation } from "./FolderLocation";
import { DeleteDialog, MoveDialog, OwnerDialog, useReservation } from "./OrganizeDialogs";
import { NotFoundPage } from "./NotFoundPage";
import { BackgroundActions, useActionRunner } from "./ActionRunner";
import { ObjectActionBar, type ActionHandlers } from "./ObjectActions";

type Tab = "details" | "structure" | "where-used" | "documents" | "replacements" | "parts" | "discussion" | "history" | "audit";

/** Remounts per object, so page state never leaks between objects. */
export function ObjectPage() {
  const id = useIdParam();
  return <ObjectView key={id} id={id} />;
}

function ObjectView({ id }: { id: string }) {
  const { data: obj, error, isLoading } = useObject(id);
  useRecordVisit(obj?.id);
  const { data: me } = useMe();
  const shows = useShows();
  const [params, setParams] = useSearchParams();
  const { data: comments } = useComments(id);
  const discussionCount = comments?.filter((c) => !c.parentId && !c.resolved).length ?? 0;
  const { data: allTypes } = useTypes();

  if (isLoading) return <Loading />;
  if (isApiError(error, 404)) return <NotFoundPage what="object" />;
  if (error || !obj) return <ErrorBanner error={error} />;

  const isPart = allTypes?.find((ty) => ty.id === obj.typeId)?.baseClass === "PART";
  const tabs: [Tab, string][] = [
    ["details", t("Details")],
    ...(isPart
      ? [
          ["structure", t("Structure")] as [Tab, string],
          ["where-used", t("Where used")] as [Tab, string],
          ["documents", t("Documents")] as [Tab, string],
          ["replacements", t("Replacements")] as [Tab, string],
        ]
      : allTypes
        ? [["parts", t("Parts")] as [Tab, string]]
        : []),
    ["discussion", discussionCount ? t("Discussion ({count})", { count: discussionCount }) : t("Discussion")],
    ...(shows("object.history") ? [["history", t("History")] as [Tab, string]] : []),
    ...(me?.siteAdmin && shows("object.audit") ? [["audit", t("Audit")] as [Tab, string]] : []),
  ];
  const asked = params.get("tab") as Tab | null;
  const tab: Tab = asked && tabs.some(([k]) => k === asked) ? asked : "details";
  const myId = me?.user.id;

  return (
    <>
      <ObjectHeader obj={obj} myId={myId} tabs={<Tabs tabs={tabs} active={tab} onSelect={(k) => setParams({ tab: k })} />} />
      <div className="content">
        {tab === "details" && <DetailsTab obj={obj} myId={myId} />}
        {tab === "structure" && <StructureTab obj={obj} editable={!!myId && obj.checkedOutBy === myId} />}
        {tab === "where-used" && <WhereUsedTab obj={obj} />}
        {tab === "documents" && <DocumentLinksTab obj={obj} editable={!!myId && obj.checkedOutBy === myId} />}
        {tab === "parts" && <LinkingPartsTab obj={obj} />}
        {tab === "replacements" && <ReplacementsTab obj={obj} />}
        {tab === "discussion" && <DiscussionTab obj={obj} />}
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
  const [docDialog, setDocDialog] = useState<"save-as" | "compare" | "move" | "delete" | "owner" | null>(null);
  const clipboard = useClipboard();
  const reservation = useReservation(obj);
  const { data: me } = useMe();
  const { data: contextPermissions } = usePermissions(obj.contextId);
  const canShare = !!me?.siteAdmin || obj.owner === myId || !!contextPermissions?.has("MANAGE");
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
    "object.save-as": () => setDocDialog("save-as"),
    "object.compare": () => setDocDialog("compare"),
    "object.move": () => setDocDialog("move"),
    "object.delete": () => setDocDialog("delete"),
    "object.change-owner": () => setDocDialog("owner"),
    "object.reserve": () => reservation.reserve.mutate(),
    "object.release": () => reservation.release.mutate(),
    "object.add-to-clipboard": () => clipboard.add({ id: obj.id, number: obj.number, name: obj.name, version: obj.version }),
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
                {mine ? (
                  t("Checked out by you")
                ) : (
                  <>
                    {t("Checked out by")} <PrincipalName id={obj.checkedOutBy} />
                  </>
                )}
              </span>
            )}
            {obj.reservedBy && (
              <span className="tag" data-testid="reserved">
                <Bookmark size={11} aria-hidden />
                {obj.reservedBy === myId ? (
                  t("Reserved by you")
                ) : (
                  <>
                    {t("Reserved by")} <PrincipalName id={obj.reservedBy} />
                  </>
                )}
              </span>
            )}
            <span className="sub">{typeName}</span>
            <span className="track-btns">
              <FavoriteButton kind="OBJECT" targetId={obj.id} label={`${obj.number} ${obj.name}`} />
              <SubscribeButton kind="OBJECT" targetId={obj.id} label={`${obj.number} ${obj.name}`} />
              {canShare && <ShareButton obj={obj} />}
            </span>
          </>
        }
        toolbar={
          available ? (
            <ObjectActionBar actions={available} handlers={handlers} busy={busy || runner.busy} stateLabel={label} onInvoke={runner.run} />
          ) : undefined
        }
        tabs={tabs}
      />
      {(reservation.reserve.error ?? reservation.release.error) != null && (
        <div style={{ padding: "12px 16px 0" }}>
          <ErrorBanner error={reservation.reserve.error ?? reservation.release.error} />
        </div>
      )}
      {failed != null && (
        <div style={{ padding: "12px 16px 0" }}>
          <ErrorBanner error={failed} />
        </div>
      )}
      {runner.ui}
      <BackgroundActions objectId={obj.id} refreshKey={runner.started} />
      {renaming && <RenameDialog obj={obj} onClose={() => setRenaming(false)} />}
      {docDialog === "save-as" && <SaveAsDialog obj={obj} onClose={() => setDocDialog(null)} />}
      {docDialog === "compare" && <CompareDialog obj={obj} onClose={() => setDocDialog(null)} />}
      {docDialog === "move" && <MoveDialog obj={obj} onClose={() => setDocDialog(null)} />}
      {docDialog === "delete" && <DeleteDialog obj={obj} onClose={() => setDocDialog(null)} />}
      {docDialog === "owner" && <OwnerDialog obj={obj} onClose={() => setDocDialog(null)} />}
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
        title={t("Check in {number}", { number: obj.number })}
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
              {t("Cancel")}
            </button>
            <button
              className="btn primary"
              disabled={busy || (commentRequired && !comment.trim())}
              title={commentRequired && !comment.trim() ? t("A comment is required here") : undefined}
              onClick={() =>
                actions.checkIn.mutate(comment, {
                  onSuccess: () => {
                    setCheckingIn(false);
                    setComment("");
                  },
                })
              }
            >
              {t("Check in")}
            </button>
          </>
        }
      >
        <ErrorBanner error={actions.checkIn.error} />
        <div className="field">
          <div className="label-row">
            <label htmlFor="checkin-comment">{t("Comment")}</label>
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
            placeholder={t("What changed?")}
            autoFocus
          />
        </div>
      </Dialog>
    </>
  );
}

function stateName(lifecycle: ObjectLifecycle | undefined, state: string): string {
  const labels = lifecycle?.states.find((s) => s.key === state)?.labels;
  return labels?.[language()] ?? labels?.en ?? state.charAt(0) + state.slice(1).toLowerCase().replace(/_/g, " ");
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
      title={t("Change the lifecycle of {number}", { number: obj.number })}
      onClose={onClose}
      footer={
        <>
          <button className="btn" type="button" onClick={onClose}>
            {t("Cancel")}
          </button>
          <button
            className="btn primary"
            type="submit"
            form="lifecycle-form"
            disabled={change.isPending || !chosen || !target || (chosen.id === current.lifecycleId && target === obj.latest.state)}
          >
            {t("Move")}
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
          {t("Now: {lifecycle} version {version}", { lifecycle: current.lifecycleName, version: current.lifecycleVersion })}
          {!current.lifecycleLatest && ` ${t("(a newer version exists)")}`}, {stateName(current, obj.latest.state)}.{" "}
          {t("Recorded in the audit trail.")}
        </p>
        <div className="field">
          <label htmlFor="lc-target">{t("Lifecycle")}</label>
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
                {t("{name} (version {version})", { name: pickLabel(l.labels, l.name), version: l.version })}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="lc-state">{t("State")}</label>
          <select id="lc-state" value={target} onChange={(e) => setState(e.target.value)}>
            {!target && <option value="">{t("Choose…")}</option>}
            {chosen?.states.map((s) => (
              <option key={s.key} value={s.key}>
                {pickLabel(s.labels, s.key)}
              </option>
            ))}
          </select>
          {!keeps && chosen && (
            <span className="hint">
              {t("This lifecycle has no {state} state: choose one.", { state: stateName(current, obj.latest.state) })}
            </span>
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
      title={t("Rename or renumber {number}", { number: obj.number })}
      onClose={onClose}
      footer={
        <>
          <button className="btn" type="button" onClick={onClose}>
            {t("Cancel")}
          </button>
          <button
            className="btn primary"
            type="submit"
            form="rename-form"
            disabled={rename.isPending || !changed || !name.trim() || !number.trim()}
          >
            {t("Save")}
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
          {t("Applies to every version. The old number and name stay in the audit trail.")}
        </p>
        <div className="field">
          <label htmlFor="rename-number">{t("Number")}</label>
          <input id="rename-number" value={number} onChange={(e) => setNumber(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="rename-name">{t("Name")}</label>
          <input id="rename-name" value={name} onChange={(e) => setName(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="rename-comment">{t("Reason")}</label>
          <input
            id="rename-comment"
            value={comment}
            onChange={(e) => setComment(e.target.value)}
            placeholder={t("e.g. Align with the ERP number")}
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
      title={t("Set state of {number}", { number: objectNumber })}
      onClose={onCancel}
      footer={
        <>
          <button className="btn" onClick={onCancel}>
            {t("Cancel")}
          </button>
          <button className="btn primary" disabled={busy} onClick={() => onConfirm(comment)}>
            {t("Set state")}
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
            <div className="banner warn">{t("This ignores the lifecycle's transitions. It is recorded in the audit trail.")}</div>
          )}
          <div className="field">
            <label htmlFor="state-comment">{t("Comment")}</label>
            <textarea
              id="state-comment"
              value={comment}
              onChange={(e) => setComment(e.target.value)}
              placeholder={t("Why? e.g. approved in design review DR-12")}
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
  const { data: types } = useTypes();
  const isDocument = types?.find((ty) => ty.id === obj.typeId)?.baseClass === "DOCUMENT";
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
            <Panel title={t("Working copy")}>
              <ErrorBanner error={workingCopy.error} />
              <Loading />
            </Panel>
          )
        ) : (
          <Panel title={t("Attributes")} note={t("as of {version}", { version: latest.version })}>
            {definitions && infoLayout ? (
              <AttributeValues definitions={definitions} groups={infoLayout.groups} values={latest.attributes as Attributes} />
            ) : (
              <Loading />
            )}
          </Panel>
        )}
        <Panel title={t("General")}>
          <Props
            items={[
              [t("Number"), obj.number],
              [t("Name"), obj.name],
              [t("Type"), typeName],
              [t("Version"), <span className="version">{latest.version}</span>],
              [t("State"), <StateBadge state={latest.state} />],
              [t("Context"), context ? <Link to={`/contexts/${obj.contextId}`}>{context.name}</Link> : null],
              [t("Folder"), <FolderLocation obj={obj} />],
              [t("Owner"), <PrincipalName id={obj.owner} />],
              [t("Checked out by"), obj.checkedOutBy ? <PrincipalName id={obj.checkedOutBy} /> : null],
              [t("Reserved by"), obj.reservedBy ? <PrincipalName id={obj.reservedBy} /> : null],
              [
                t("Last checked in"),
                <>
                  {formatDate(latest.checkedInAt)} · <PrincipalName id={latest.createdBy} />
                </>,
              ],
              [t("Comment"), latest.comment],
              [
                t("Created"),
                <>
                  {formatDate(obj.createdAt)} · <PrincipalName id={obj.createdBy} />
                </>,
              ],
            ]}
          />
        </Panel>
      </div>
      {/* Only documents hold files (primary content and attachments). */}
      <ReferencedBy objectId={obj.id} />
      {isDocument && <ObjectFiles objectId={obj.id} iterationKey={mine ? `wc:${workingCopy.data?.id ?? ""}` : latest.id} editable={mine} />}
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
          {t("Working copy")} <span className="version">{version}</span>
        </h2>
        <span className="note">{t("Only you see these edits until you check in.")}</span>
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
          {t("Save attributes")}
        </button>
        {dirty ? (
          <span className="muted">{t("Unsaved changes")}</span>
        ) : (
          updateAttributes.isSuccess && <span className="muted">{t("Saved.")}</span>
        )}
      </div>
    </form>
  );
}

function HistoryTab({ id }: { id: string }) {
  const { data, isLoading, error } = useHistory(id);
  return (
    <Panel title={t("Iterations")} note={data ? String(data.length) : undefined} flush>
      <ErrorBanner error={error} />
      {isLoading ? (
        <Loading />
      ) : (
        <div className="grid-wrap">
          <table className="grid" aria-label={t("Iteration history")}>
            <thead>
              <tr>
                <th>{t("Version")}</th>
                <th>{t("State")}</th>
                <th>{t("Comment")}</th>
                <th>{t("Checked in by")}</th>
                <th>{t("Checked in")}</th>
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
    <Panel title={t("Audit trail")} note={data ? String(data.length) : undefined} flush>
      <ErrorBanner error={error} />
      {isLoading ? (
        <Loading />
      ) : (
        <div className="grid-wrap">
          <table className="grid" aria-label={t("Audit trail")}>
            <thead>
              <tr>
                <th>{t("When")}</th>
                <th>{t("Action")}</th>
                <th>{t("Who")}</th>
                <th>{t("Details")}</th>
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
                      ` · ${t("was {oldNumber} “{oldName}”, now {number} “{name}”", { oldNumber: String(entry.details.previousNumber), oldName: String(entry.details.previousName), number: String(entry.details.number), name: String(entry.details.name) })}`}
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
