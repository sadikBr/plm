import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ClipboardList, Plus, Trash2 } from "lucide-react";
import { useState } from "react";
import { Link, useNavigate } from "react-router";
import { api, unwrap, type Schemas } from "../api/client";
import { useContextsOfKind, useTypes, type AttributeDefinition, type VersionedObject } from "../api/queries";
import { Dialog, ErrorBanner, Loading, Panel, PrincipalName, StateBadge, TypeIcon, formatDate, typeLabel } from "../components/common";
import { PageHeader } from "../components/Layout";
import { ReferenceInput } from "../components/References";
import { t, tn } from "../i18n/i18n";

type Kind = Schemas["ChangeView"]["kind"];
type Role = Schemas["ChangeLink"]["role"];
type Link_ = Schemas["ChangeLink"];

/** Change management (roadmap 5.8): problem reports, change requests, notices and their tasks. */

/** The built-in change board process (roadmap 5.9). */
const CHANGE_BOARD = "00000000-0000-7000-8000-000000000503";

export const CHANGE_TYPES: Record<Kind, string> = {
  PROBLEM_REPORT: "00000000-0000-7000-8000-000000000104",
  CHANGE_REQUEST: "00000000-0000-7000-8000-000000000105",
  CHANGE_NOTICE: "00000000-0000-7000-8000-000000000106",
  CHANGE_TASK: "00000000-0000-7000-8000-000000000107",
};

export function kindLabel(k: Kind): string {
  switch (k) {
    case "PROBLEM_REPORT":
      return t("Problem report");
    case "CHANGE_REQUEST":
      return t("Change request");
    case "CHANGE_NOTICE":
      return t("Change notice");
    case "CHANGE_TASK":
      return t("Change task");
  }
  return k;
}

export function roleLabel(r: Role): string {
  switch (r) {
    case "AFFECTED":
      return t("affected");
    case "RESULTING":
      return t("resulting");
    case "END_ITEM":
      return t("end item");
    case "ADDRESSES":
      return t("addresses");
    case "IMPLEMENTS":
      return t("implements");
    case "TASK":
      return t("task");
  }
  return r;
}

/** Which kind of change object a type is, following its parents. */
export function changeKindOf(types: { id: string; parentId?: string | null }[] | undefined, typeId: string): Kind | undefined {
  const byType = Object.entries(CHANGE_TYPES).map(([k, v]) => [v, k as Kind] as const);
  for (let cur = types?.find((x) => x.id === typeId); cur; cur = types?.find((x) => x.id === cur!.parentId)) {
    const hit = byType.find(([v]) => v === cur!.id);
    if (hit) return hit[1];
  }
  return undefined;
}

/** A chain link seen from its target: the request addressing this report, the notice of this task. */
function inverseLabel(r: Role): string {
  switch (r) {
    case "ADDRESSES":
      return t("addressed by");
    case "IMPLEMENTS":
      return t("implemented by");
    case "TASK":
      return t("task of");
  }
  return roleLabel(r);
}

/** Every change object the user can see, newest first, through search. */
export function useChanges() {
  const { data: types } = useTypes();
  const ids = (types ?? []).filter((x) => x.baseClass === "CHANGE").map((x) => x.id);
  return useQuery({
    queryKey: ["changes", "list", ids],
    queryFn: () =>
      unwrap(
        api.POST("/api/v1/search", {
          body: { typeIds: ids, states: [], contextIds: [], attributes: {}, offset: 0, limit: 200, sort: "MODIFIED" },
        }),
      ),
    enabled: ids.length > 0,
  });
}

export function ChangesPage() {
  const { data, isLoading, error } = useChanges();
  const [creating, setCreating] = useState<Kind | null>(null);
  const [kind, setKind] = useState<Kind | "">("");
  const rows = (data?.hits ?? []).filter((h) => !kind || h.typeId === CHANGE_TYPES[kind]);
  return (
    <>
      <PageHeader
        icon={<ClipboardList size={18} className="icon-muted" aria-hidden />}
        title={<span className="nm">{t("Changes")}</span>}
        meta={<span className="sub">{t("Problem reports, change requests, change notices and their tasks.")}</span>}
      />
      <div className="content">
        <Panel
          title={t("Changes")}
          flush
          actions={
            <>
              <select className="filter" aria-label={t("Kind")} value={kind} onChange={(e) => setKind(e.target.value as Kind | "")}>
                <option value="">{t("All kinds")}</option>
                {(Object.keys(CHANGE_TYPES) as Kind[]).map((k) => (
                  <option key={k} value={k}>
                    {kindLabel(k)}
                  </option>
                ))}
              </select>
              {(["PROBLEM_REPORT", "CHANGE_REQUEST", "CHANGE_NOTICE"] as Kind[]).map((k) => (
                <button key={k} className="btn small" onClick={() => setCreating(k)}>
                  <Plus size={13} aria-hidden /> {kindLabel(k)}
                </button>
              ))}
            </>
          }
        >
          <ErrorBanner error={error} />
          {isLoading ? (
            <Loading />
          ) : !rows.length ? (
            <div className="empty">{t("No change yet.")}</div>
          ) : (
            <table className="grid" aria-label={t("Changes")}>
              <thead>
                <tr>
                  <th>{t("Number")}</th>
                  <th>{t("Name")}</th>
                  <th>{t("Kind")}</th>
                  <th>{t("State")}</th>
                  <th>{t("Last modified")}</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((h) => (
                  <tr key={h.id}>
                    <td className="nowrap">
                      <TypeIcon typeId={h.typeId} /> <Link to={`/objects/${h.id}?tab=change`}>{h.number}</Link>
                    </td>
                    <td>{h.name}</td>
                    <td>
                      <TypeName id={h.typeId} />
                    </td>
                    <td>
                      <StateBadge state={h.state} />
                    </td>
                    <td className="nowrap">{formatDate(h.modifiedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Panel>
      </div>
      {creating && <NewChangeDialog kind={creating} onClose={() => setCreating(null)} />}
    </>
  );
}

function TypeName({ id }: { id: string }) {
  const { data: types } = useTypes();
  return <>{typeLabel(types?.find((x) => x.id === id))}</>;
}

function NewChangeDialog({ kind, onClose }: { kind: Kind; onClose: () => void }) {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { data: products } = useContextsOfKind("PRODUCT");
  const [name, setName] = useState("");
  const [contextId, setContextId] = useState("");
  const create = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/api/v1/objects", {
          body: { typeId: CHANGE_TYPES[kind], contextId: contextId || products![0]!.id, name: name.trim(), attributes: {} },
        }),
      ),
    onSuccess: (o) => {
      void qc.invalidateQueries({ queryKey: ["changes"] });
      navigate(`/objects/${o.id}?tab=change`);
    },
  });
  return (
    <Dialog
      open
      title={t("New {kind}", { kind: kindLabel(kind).toLowerCase() })}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            {t("Cancel")}
          </button>
          <button className="btn primary" disabled={!name.trim() || !products?.length || create.isPending} onClick={() => create.mutate()}>
            {t("Create")}
          </button>
        </>
      }
    >
      <div className="attr-form">
        <div className="field">
          <label htmlFor="chg-name">{t("Name")}</label>
          <input id="chg-name" value={name} onChange={(e) => setName(e.target.value)} autoFocus />
        </div>
        <div className="field">
          <label htmlFor="chg-context">{t("In")}</label>
          <select id="chg-context" value={contextId} onChange={(e) => setContextId(e.target.value)}>
            {(products ?? []).map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
        </div>
        <ErrorBanner error={create.error} />
      </div>
    </Dialog>
  );
}

const ANY_OBJECT = {
  name: "target",
  dataType: "REFERENCE",
  referenceTypeId: null,
  labels: { en: "Object" },
} as unknown as AttributeDefinition;

function ObjectCell({ o }: { o: Schemas["LinkedObject"] }) {
  if (!o.readable) return <span className="muted">{t("(no access)")}</span>;
  return (
    <span className="nowrap">
      {o.typeId && <TypeIcon typeId={o.typeId} />} <Link to={`/objects/${o.id}`}>{o.number}</Link> {o.name}
    </span>
  );
}

/** A change object's own tab: its chain, what it affects and produces, and the next step. */
export function ChangeTab({ obj, kind }: { obj: VersionedObject; kind: Kind }) {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const { data, error, isLoading } = useQuery({
    queryKey: ["change", obj.id],
    queryFn: () => unwrap(api.GET("/api/v1/changes/{id}", { params: { path: { id: obj.id } } })),
  });
  const refresh = () => void qc.invalidateQueries({ queryKey: ["change", obj.id] });
  const remove = useMutation({
    mutationFn: (linkId: string) => unwrap(api.DELETE("/api/v1/changes/{id}/links/{linkId}", { params: { path: { id: obj.id, linkId } } })),
    onSuccess: refresh,
  });
  const next = useMutation({
    mutationFn: (k: Kind) =>
      unwrap(
        api.POST("/api/v1/changes/from", {
          body: { fromId: obj.id, kind: k, name: k === "CHANGE_TASK" ? t("Task of {number}", { number: obj.number }) : obj.name },
        }),
      ),
    onSuccess: (r) => navigate(`/objects/${r.id}?tab=change`),
  });
  const board = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/api/v1/processes", {
          body: { templateId: CHANGE_BOARD, subjectId: obj.id, comment: t("Please decide on {number}", { number: obj.number }) },
        }),
      ),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["object", obj.id] });
      navigate(`/objects/${obj.id}?tab=processes`);
    },
  });
  if (isLoading) return <Loading />;
  if (error || !data) return <ErrorBanner error={error} />;
  const of = (r: Role) => data.links.filter((l) => l.role === r);
  const chain = [...data.links.filter((l) => ["ADDRESSES", "IMPLEMENTS", "TASK"].includes(l.role)), ...data.linkedFrom];
  const section = (role: Role, title: string, hint: string) => (
    <Panel title={title} note={hint} flush>
      <LinkTable links={of(role)} onRemove={(id) => remove.mutate(id)} />
      <AddLink changeId={obj.id} role={role} onAdded={refresh} />
    </Panel>
  );
  return (
    <div className="details-stack">
      <Panel
        title={kindLabel(kind)}
        actions={
          <>
            {kind === "PROBLEM_REPORT" && (
              <>
                <button className="btn small primary" onClick={() => next.mutate("CHANGE_REQUEST")} disabled={next.isPending}>
                  {t("Create a change request")}
                </button>
                <button
                  className="btn small"
                  onClick={() => next.mutate("CHANGE_NOTICE")}
                  disabled={next.isPending}
                  title={t("Fast track: no change request")}
                >
                  {t("Create a change notice (fast track)")}
                </button>
              </>
            )}
            {kind === "CHANGE_REQUEST" && obj.latest.state === "OPEN" && (
              <button className="btn small primary" onClick={() => board.mutate()} disabled={board.isPending}>
                {t("Submit to the change board")}
              </button>
            )}
            {kind === "CHANGE_REQUEST" && obj.latest.state === "APPROVED" && (
              <button className="btn small primary" onClick={() => next.mutate("CHANGE_NOTICE")} disabled={next.isPending}>
                {t("Create a change notice")}
              </button>
            )}
            {kind === "CHANGE_NOTICE" && (
              <button className="btn small primary" onClick={() => next.mutate("CHANGE_TASK")} disabled={next.isPending}>
                {t("Add a change task")}
              </button>
            )}
          </>
        }
      >
        <ErrorBanner error={next.error ?? remove.error ?? board.error} />
        {chain.length === 0 ? (
          <div className="muted">{t("Not linked to other changes yet.")}</div>
        ) : (
          <ul className="change-chain" aria-label={t("Related changes")}>
            {chain.map((l) => {
              const other = l.change ?? l.target;
              return (
                <li key={l.id}>
                  <span className="tag">{l.change ? inverseLabel(l.role) : roleLabel(l.role)}</span> <ObjectCell o={other} />{" "}
                  {other.state && <StateBadge state={other.state} />}
                </li>
              );
            })}
          </ul>
        )}
      </Panel>
      {kind === "CHANGE_NOTICE" && <PlanPanel noticeId={obj.id} plan={data.plan} onChanged={refresh} />}
      {section("AFFECTED", t("Affected objects"), t("What the change is about: the versions to change."))}
      {(kind === "CHANGE_NOTICE" || kind === "CHANGE_TASK") &&
        section("RESULTING", t("Resulting objects"), t("The new versions the change produces."))}
      {kind !== "CHANGE_TASK" && section("END_ITEM", t("End items"), t("The products concerned."))}
    </div>
  );
}

function LinkTable({ links, onRemove }: { links: Link_[]; onRemove: (id: string) => void }) {
  if (!links.length) return <div className="empty small">{t("None yet.")}</div>;
  return (
    <table className="grid">
      <thead>
        <tr>
          <th>{t("Object")}</th>
          <th>{t("Version then")}</th>
          <th>{t("Now")}</th>
          <th>{t("Note")}</th>
          <th />
        </tr>
      </thead>
      <tbody>
        {links.map((l) => (
          <tr key={l.id}>
            <td>
              <ObjectCell o={l.target} />
            </td>
            <td>{l.linkedVersion}</td>
            <td className="nowrap">
              {l.target.version} {l.target.state && <StateBadge state={l.target.state} />}
            </td>
            <td>{l.note}</td>
            <td className="num">
              <button
                className="icon-btn"
                aria-label={t("Remove {number}", { number: l.target.number ?? "" })}
                onClick={() => onRemove(l.id)}
              >
                <Trash2 size={13} />
              </button>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function AddLink({ changeId, role, onAdded }: { changeId: string; role: Role; onAdded: () => void }) {
  const add = useMutation({
    mutationFn: (targetId: string) =>
      unwrap(api.POST("/api/v1/changes/{id}/links", { params: { path: { id: changeId } }, body: { targetId, role } })),
    onSuccess: onAdded,
  });
  return (
    <div className="add-link">
      <ReferenceInput id={`add-${role}`} def={ANY_OBJECT} value={undefined} onChange={(v) => v && add.mutate(String(v))} />
      <ErrorBanner error={add.error} />
    </div>
  );
}

/** On a part or document: the changes it's in (roadmap 5.8). */
export function ObjectChangesTab({ obj }: { obj: VersionedObject }) {
  const { data, error, isLoading } = useQuery({
    queryKey: ["objectChanges", obj.id],
    queryFn: () => unwrap(api.GET("/api/v1/objects/{id}/changes", { params: { path: { id: obj.id } } })),
  });
  return (
    <Panel title={t("Changes")} flush>
      <ErrorBanner error={error} />
      {isLoading ? (
        <Loading />
      ) : !data?.length ? (
        <div className="empty">{t("No change concerns it.")}</div>
      ) : (
        <table className="grid" aria-label={t("Changes")}>
          <thead>
            <tr>
              <th>{t("Change")}</th>
              <th>{t("State")}</th>
              <th>{t("As")}</th>
              <th>{t("Version then")}</th>
            </tr>
          </thead>
          <tbody>
            {data.map((l) => (
              <tr key={l.id}>
                <td>{l.change && <ObjectCell o={l.change} />}</td>
                <td>{l.change?.state && <StateBadge state={l.change.state} />}</td>
                <td>{roleLabel(l.role)}</td>
                <td>{l.linkedVersion}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <div className="muted small" style={{ padding: "8px 12px" }}>
        {tn("{n} change", "{n} changes", new Set((data ?? []).map((l) => l.changeId)).size)}
      </div>
    </Panel>
  );
}

/** A change notice's implementation plan (roadmap 5.9): its tasks, who does them, by when. */
function PlanPanel({ noticeId, plan, onChanged }: { noticeId: string; plan: Schemas["PlanEntry"][]; onChanged: () => void }) {
  if (!plan.length) return null;
  const done = plan.filter((p) => p.task.state === "RESOLVED" || p.task.state === "CANCELLED").length;
  return (
    <Panel title={t("Implementation plan")} note={t("{done} of {all} tasks done", { done, all: plan.length })} flush>
      <table className="grid" aria-label={t("Implementation plan")}>
        <thead>
          <tr>
            <th>{t("Task")}</th>
            <th>{t("State")}</th>
            <th>{t("Who")}</th>
            <th>{t("Due")}</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {plan.map((p) => (
            <PlanRow key={p.task.id} noticeId={noticeId} entry={p} onChanged={onChanged} />
          ))}
        </tbody>
      </table>
    </Panel>
  );
}

function PlanRow({ noticeId, entry, onChanged }: { noticeId: string; entry: Schemas["PlanEntry"]; onChanged: () => void }) {
  const [who, setWho] = useState("");
  const [due, setDue] = useState(entry.dueDate ?? "");
  const save = useMutation({
    mutationFn: () =>
      unwrap(
        api.PUT("/api/v1/changes/{id}/plan/{taskId}", {
          params: { path: { id: noticeId, taskId: entry.task.id } },
          body: { assignee: who.trim() || null, dueDate: due || null },
        }),
      ),
    onSuccess: () => {
      setWho("");
      onChanged();
    },
  });
  return (
    <tr>
      <td>
        <ObjectCell o={entry.task} />
      </td>
      <td>{entry.task.state && <StateBadge state={entry.task.state} />}</td>
      <td>
        {entry.assignee && (
          <span style={{ marginRight: 6 }}>
            <PrincipalName id={entry.assignee} />
          </span>
        )}
        <input
          className="small-input"
          aria-label={t("Assign to (username)")}
          placeholder={entry.assignee ? t("change…") : t("username")}
          value={who}
          onChange={(e) => setWho(e.target.value)}
        />
      </td>
      <td className="nowrap">
        <input type="date" aria-label={t("Due date")} value={due} onChange={(e) => setDue(e.target.value)} />{" "}
        {entry.overdue && <span className="tag deny">{t("overdue")}</span>}
      </td>
      <td className="num">
        <button className="btn small" onClick={() => save.mutate()} disabled={save.isPending}>
          {t("Save")}
        </button>
        <ErrorBanner error={save.error} />
      </td>
    </tr>
  );
}
