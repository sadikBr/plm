import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ClipboardList, PencilRuler, Plus, Trash2 } from "lucide-react";
import { useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router";
import { api, unwrap, type Schemas } from "../api/client";
import { useContextsOfKind, useTypes, type AttributeDefinition, type VersionedObject } from "../api/queries";
import { Dialog, ErrorBanner, Loading, Panel, PrincipalName, StateBadge, TypeIcon, formatDate, typeLabel } from "../components/common";
import { PageHeader, Tabs } from "../components/Layout";
import { ChangeDashboard } from "./ChangeDashboard";
import { ReferenceInput } from "../components/References";
import { useAttributeDefinitions } from "../components/ObjectTable";
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
  VARIANCE: "00000000-0000-7000-8000-000000000108",
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
    case "VARIANCE":
      return t("Variance");
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
  const [fromTemplate, setFromTemplate] = useState(false);
  const [kind, setKind] = useState<Kind | "">("");
  const [params, setParams] = useSearchParams();
  const view = params.get("view") === "dashboard" ? "dashboard" : "list";
  const rows = (data?.hits ?? []).filter((h) => !kind || h.typeId === CHANGE_TYPES[kind]);
  return (
    <>
      <PageHeader
        icon={<ClipboardList size={18} className="icon-muted" aria-hidden />}
        title={<span className="nm">{t("Changes")}</span>}
        meta={<span className="sub">{t("Problem reports, change requests, change notices and their tasks.")}</span>}
        tabs={
          <Tabs
            tabs={[
              ["list", t("All changes")],
              ["dashboard", t("Dashboard")],
            ]}
            active={view}
            onSelect={(v) => setParams(v === "list" ? {} : { view: v })}
          />
        }
      />
      <div className="content">
        {view === "dashboard" ? (
          <ChangeDashboard />
        ) : (
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
                <button className="btn small" onClick={() => setFromTemplate(true)}>
                  {t("From a template…")}
                </button>
                {(["PROBLEM_REPORT", "CHANGE_REQUEST", "CHANGE_NOTICE", "VARIANCE"] as Kind[]).map((k) => (
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
        )}
      </div>
      {creating && <NewChangeDialog kind={creating} onClose={() => setCreating(null)} />}
      {fromTemplate && <FromTemplateDialog onClose={() => setFromTemplate(false)} />}
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
      {(kind === "CHANGE_NOTICE" || kind === "CHANGE_TASK") && (
        <RedlinesPanel change={obj} affected={of("AFFECTED").map((l) => l.target)} onApplied={refresh} />
      )}
      {(kind === "CHANGE_NOTICE" || kind === "CHANGE_TASK") &&
        section("RESULTING", t("Resulting objects"), t("The new versions the change produces."))}
      {kind === "VARIANCE" && <VarianceScopePanel changeId={obj.id} />}
      {kind !== "CHANGE_TASK" && kind !== "VARIANCE" && section("END_ITEM", t("End items"), t("The products concerned."))}
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

function FromTemplateDialog({ onClose }: { onClose: () => void }) {
  const navigate = useNavigate();
  const { data: templates } = useQuery({ queryKey: ["changeTemplates"], queryFn: () => unwrap(api.GET("/api/v1/change-templates")) });
  const { data: products } = useContextsOfKind("PRODUCT");
  const active = (templates ?? []).filter((x) => x.active);
  const [templateId, setTemplateId] = useState("");
  const [contextId, setContextId] = useState("");
  const [name, setName] = useState("");
  const chosen = templateId || active[0]?.id || "";
  const use = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/api/v1/change-templates/{id}/use", {
          params: { path: { id: chosen } },
          body: { contextId: contextId || products![0]!.id, name: name.trim() || null },
        }),
      ),
    onSuccess: (r) => navigate(`/objects/${r.id}?tab=change`),
  });
  const template = active.find((x) => x.id === chosen);
  return (
    <Dialog
      open
      title={t("New change from a template")}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            {t("Cancel")}
          </button>
          <button className="btn primary" disabled={!chosen || use.isPending} onClick={() => use.mutate()}>
            {t("Create")}
          </button>
        </>
      }
    >
      <div className="attr-form">
        <div className="field">
          <label htmlFor="tpl-id">{t("Template")}</label>
          <select id="tpl-id" value={chosen} onChange={(e) => setTemplateId(e.target.value)}>
            {active.map((x) => (
              <option key={x.id} value={x.id}>
                {x.name}
              </option>
            ))}
          </select>
          {template?.description && <span className="hint">{template.description}</span>}
          {!!template?.tasks.length && <span className="hint">{t("With tasks: {tasks}", { tasks: template.tasks.join(", ") })}</span>}
        </div>
        <div className="field">
          <label htmlFor="tpl-name">{t("Name")}</label>
          <input
            id="tpl-name"
            value={name}
            placeholder={template?.title ?? template?.name ?? ""}
            onChange={(e) => setName(e.target.value)}
          />
        </div>
        <div className="field">
          <label htmlFor="tpl-context">{t("In")}</label>
          <select id="tpl-context" value={contextId} onChange={(e) => setContextId(e.target.value)}>
            {(products ?? []).map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
        </div>
        <ErrorBanner error={use.error} />
      </div>
    </Dialog>
  );
}

/** A variance's scope (roadmap 5.10): deviation or waiver, until when, for how many. */
function VarianceScopePanel({ changeId }: { changeId: string }) {
  const qc = useQueryClient();
  const { data } = useQuery({
    queryKey: ["variance", changeId],
    queryFn: () => unwrap(api.GET("/api/v1/changes/{id}/variance", { params: { path: { id: changeId } } })),
  });
  return data === undefined ? null : (
    <VarianceForm
      key={JSON.stringify(data)}
      changeId={changeId}
      scope={data}
      onSaved={() => void qc.invalidateQueries({ queryKey: ["variance", changeId] })}
    />
  );
}

function VarianceForm({ changeId, scope, onSaved }: { changeId: string; scope: Schemas["VarianceScope"] | null; onSaved: () => void }) {
  const [kind, setKind] = useState<Schemas["VarianceScope"]["kind"]>(scope?.kind ?? "DEVIATION");
  const [until, setUntil] = useState(scope?.validUntil ?? "");
  const [quantity, setQuantity] = useState(scope?.quantity ? String(scope.quantity) : "");
  const [reason, setReason] = useState(scope?.reason ?? "");
  const save = useMutation({
    mutationFn: () =>
      unwrap(
        api.PUT("/api/v1/changes/{id}/variance", {
          params: { path: { id: changeId } },
          body: { kind, validUntil: until || null, quantity: quantity ? Number(quantity) : null, reason: reason.trim() || null },
        }),
      ),
    onSuccess: onSaved,
  });
  return (
    <Panel title={t("Scope")} note={t("A variance is in force once approved, on its affected objects, within its scope.")}>
      <div className="attr-form cols-3">
        <div className="field">
          <label htmlFor="var-kind">{t("Kind")}</label>
          <select id="var-kind" value={kind} onChange={(e) => setKind(e.target.value as typeof kind)}>
            <option value="DEVIATION">{t("Deviation (before making)")}</option>
            <option value="WAIVER">{t("Waiver (after making)")}</option>
          </select>
        </div>
        <div className="field">
          <label htmlFor="var-until">{t("Valid until")}</label>
          <input id="var-until" type="date" value={until} onChange={(e) => setUntil(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="var-qty">{t("For a quantity of")}</label>
          <input id="var-qty" inputMode="numeric" value={quantity} onChange={(e) => setQuantity(e.target.value.replace(/\D/g, ""))} />
        </div>
        <div className="field wide">
          <label htmlFor="var-reason">{t("Why")}</label>
          <input id="var-reason" value={reason} onChange={(e) => setReason(e.target.value)} />
        </div>
        <div className="field">
          <button className="btn primary small" onClick={() => save.mutate()} disabled={save.isPending}>
            {t("Save the scope")}
          </button>
        </div>
      </div>
      <ErrorBanner error={save.error} />
    </Panel>
  );
}

/** On a part or document: the variances in force on it (roadmap 5.10). */
export function VariancesBanner({ objectId }: { objectId: string }) {
  const { data } = useQuery({
    queryKey: ["variances", objectId],
    queryFn: () => unwrap(api.GET("/api/v1/objects/{id}/variances", { params: { path: { id: objectId } } })),
  });
  if (!data?.length) return null;
  return (
    <div className="banner warn variance-banner" role="note">
      {data.map((v) => (
        <div key={v.changeId}>
          <strong>{v.scope.kind === "WAIVER" ? t("Waiver") : t("Deviation")}</strong>{" "}
          <Link to={`/objects/${v.changeId}?tab=change`}>{v.number}</Link> {v.name}
          {v.scope.validUntil && <> · {t("until {date}", { date: v.scope.validUntil })}</>}
          {v.scope.quantity && <> · {tn("for {n} piece", "for {n} pieces", v.scope.quantity)}</>}
          {v.scope.reason && <span className="muted"> — {v.scope.reason}</span>}
        </div>
      ))}
    </div>
  );
}

type Redline = Schemas["Redline"];
type RedlineOp = Redline["op"];
const PART_REF = {
  name: "part",
  dataType: "REFERENCE",
  referenceTypeId: "00000000-0000-7000-8000-000000000102",
  labels: { en: "Part" },
} as unknown as AttributeDefinition;
const FINISHED = ["RESOLVED", "CANCELLED", "REJECTED"];

function opLabel(op: RedlineOp): string {
  switch (op) {
    case "ADD":
      return t("Add a part");
    case "REMOVE":
      return t("Remove a part");
    case "QUANTITY":
      return t("Change a quantity");
    case "REPLACE":
      return t("Replace a part");
    case "SET_ATTRIBUTE":
      return t("Set an attribute");
  }
}

function shown(v: unknown): string {
  if (v === null || v === undefined || v === "") return "—";
  return typeof v === "object" ? JSON.stringify(v) : String(v);
}

/** What a redline does, in words, with its parts linked. */
function RedlineEdit({ r }: { r: Redline }) {
  const child = r.child && <ObjectCell o={r.child} />;
  switch (r.op) {
    case "ADD":
      return (
        <>
          {t("Add")} <strong>{r.quantity ?? 1} ×</strong> {child}
        </>
      );
    case "REMOVE":
      return (
        <>
          {t("Remove")} {child}
        </>
      );
    case "QUANTITY":
      return (
        <>
          {child} → <strong>{t("quantity {q}", { q: r.quantity ?? "" })}</strong>
        </>
      );
    case "REPLACE":
      return (
        <>
          {t("Replace")} {child} {t("with")} {r.newChild && <ObjectCell o={r.newChild} />}
        </>
      );
    case "SET_ATTRIBUTE":
      return (
        <>
          <code>{r.attribute}</code> → <strong>{shown(r.value)}</strong>
        </>
      );
  }
}

/** Proposed edits (roadmap 5.11): structure and attribute changes applied when the change is resolved. */
function RedlinesPanel({
  change,
  affected,
  onApplied,
}: {
  change: VersionedObject;
  affected: Schemas["LinkedObject"][];
  onApplied: () => void;
}) {
  const qc = useQueryClient();
  const key = ["redlines", change.id];
  const { data } = useQuery({
    queryKey: key,
    queryFn: () => unwrap(api.GET("/api/v1/changes/{id}/redlines", { params: { path: { id: change.id } } })),
  });
  const [adding, setAdding] = useState(false);
  const [mass, setMass] = useState(false);
  const refresh = () => {
    void qc.invalidateQueries({ queryKey: key });
    onApplied();
  };
  const remove = useMutation({
    mutationFn: (redlineId: string) =>
      unwrap(api.DELETE("/api/v1/changes/{id}/redlines/{redlineId}", { params: { path: { id: change.id, redlineId } } })),
    onSuccess: refresh,
  });
  const open = !FINISHED.includes(change.latest.state);
  const pending = (data ?? []).filter((r) => !r.appliedAt).length;
  return (
    <Panel
      title={t("Proposed edits")}
      note={
        open
          ? t("Structure and attribute edits, applied to their objects when this change is resolved.")
          : t("Applied when the change was resolved.")
      }
      flush
      actions={
        open && (
          <>
            <button
              className="btn small"
              onClick={() => setMass(true)}
              disabled={!affected.length}
              title={t("The same edit on every affected object")}
            >
              {t("Mass change…")}
            </button>
            <button className="btn small" onClick={() => setAdding((x) => !x)}>
              <PencilRuler size={14} aria-hidden /> {t("Propose an edit")}
            </button>
          </>
        )
      }
    >
      <ErrorBanner error={remove.error} />
      {adding && (
        <RedlineForm
          changeId={change.id}
          affected={affected}
          onDone={() => {
            setAdding(false);
            refresh();
          }}
        />
      )}
      {!data?.length ? (
        <div className="empty small">{t("No edits proposed.")}</div>
      ) : (
        <table className="grid" aria-label={t("Proposed edits")}>
          <thead>
            <tr>
              <th>{t("Object")}</th>
              <th>{t("Edit")}</th>
              <th>{t("Note")}</th>
              <th>{t("Status")}</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {data.map((r) => (
              <tr key={r.id}>
                <td>
                  <ObjectCell o={r.target} />
                </td>
                <td>
                  <RedlineEdit r={r} />
                </td>
                <td>{r.note ?? ""}</td>
                <td>
                  {r.appliedAt ? (
                    <span className="tag allow">{t("Applied in {version}", { version: r.appliedVersion ?? "" })}</span>
                  ) : (
                    <span className="tag">{t("Proposed")}</span>
                  )}
                </td>
                <td className="row-actions">
                  {open && !r.appliedAt && (
                    <button
                      className="btn ghost small"
                      aria-label={t("Withdraw")}
                      title={t("Withdraw")}
                      onClick={() => remove.mutate(r.id)}
                    >
                      <Trash2 size={14} aria-hidden />
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {open && pending > 0 && (
        <div className="hint" style={{ padding: "8px 16px" }}>
          {tn(
            "{n} edit will be applied when {number} is resolved: its objects are revised if released, edited and checked in.",
            "{n} edits will be applied when {number} is resolved: their objects are revised if released, edited and checked in.",
            pending,
            { number: change.number },
          )}
        </div>
      )}
      {mass && (
        <MassChangeDialog
          changeId={change.id}
          count={affected.length}
          onClose={() => {
            setMass(false);
            refresh();
          }}
        />
      )}
    </Panel>
  );
}

type EditFields = { op: RedlineOp; childId: string; newChildId: string; quantity: string; attribute: string; value: string };
const NO_FIELDS: EditFields = { op: "REPLACE", childId: "", newChildId: "", quantity: "", attribute: "", value: "" };

function body(f: EditFields, defs: Map<string, AttributeDefinition>) {
  const def = defs.get(f.attribute);
  const numeric = def && ["INTEGER", "REAL", "QUANTITY"].includes(String(def.dataType));
  const value = f.value === "" ? null : numeric ? Number(f.value) : def?.dataType === "BOOLEAN" ? f.value === "true" : f.value;
  return {
    op: f.op,
    childId: f.childId || null,
    newChildId: f.newChildId || null,
    quantity: f.quantity ? Number(f.quantity) : null,
    attribute: f.attribute || null,
    value,
  };
}

/** The fields an edit needs, by kind. */
function EditFieldset({ f, set, ops, typeIds }: { f: EditFields; set: (f: EditFields) => void; ops: RedlineOp[]; typeIds: string[] }) {
  const defs = useAttributeDefinitions(typeIds);
  const part = (field: "childId" | "newChildId", label: string) => (
    <div className="field">
      <label htmlFor={`rl-${field}`}>{label}</label>
      <ReferenceInput
        id={`rl-${field}`}
        def={PART_REF}
        value={f[field] || undefined}
        onChange={(v) => set({ ...f, [field]: v ? String(v) : "" })}
      />
    </div>
  );
  return (
    <>
      <div className="field">
        <label htmlFor="rl-op">{t("Edit")}</label>
        <select id="rl-op" value={f.op} onChange={(e) => set({ ...NO_FIELDS, op: e.target.value as RedlineOp })}>
          {ops.map((o) => (
            <option key={o} value={o}>
              {opLabel(o)}
            </option>
          ))}
        </select>
      </div>
      {f.op === "SET_ATTRIBUTE" ? (
        <>
          <div className="field">
            <label htmlFor="rl-attr">{t("Attribute")}</label>
            <select id="rl-attr" value={f.attribute} onChange={(e) => set({ ...f, attribute: e.target.value })}>
              <option value="">{t("Choose…")}</option>
              {[...defs.values()].map((d) => (
                <option key={d.name} value={d.name}>
                  {d.labels?.en ?? d.name}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor="rl-value">{t("New value")}</label>
            <input id="rl-value" value={f.value} onChange={(e) => set({ ...f, value: e.target.value })} />
          </div>
        </>
      ) : (
        <>
          {part("childId", f.op === "ADD" ? t("Part to add") : f.op === "REPLACE" ? t("Part to replace") : t("Part"))}
          {f.op === "REPLACE" && part("newChildId", t("Replace with"))}
          {(f.op === "ADD" || f.op === "QUANTITY") && (
            <div className="field">
              <label htmlFor="rl-qty">{t("Quantity")}</label>
              <input id="rl-qty" inputMode="decimal" value={f.quantity} onChange={(e) => set({ ...f, quantity: e.target.value })} />
            </div>
          )}
        </>
      )}
    </>
  );
}

function RedlineForm({ changeId, affected, onDone }: { changeId: string; affected: Schemas["LinkedObject"][]; onDone: () => void }) {
  const readable = affected.filter((a) => a.readable);
  const [targetId, setTargetId] = useState(readable[0]?.id ?? "");
  const [f, setF] = useState<EditFields>(NO_FIELDS);
  const [note, setNote] = useState("");
  const target = readable.find((a) => a.id === targetId);
  const defs = useAttributeDefinitions(target?.typeId ? [target.typeId] : []);
  const add = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/api/v1/changes/{id}/redlines", {
          params: { path: { id: changeId } },
          body: { ...body(f, defs), targetId, note: note.trim() || null },
        }),
      ),
    onSuccess: onDone,
  });
  if (!readable.length) return <div className="empty small">{t("Add affected objects first: edits are proposed on them.")}</div>;
  return (
    <div className="inline-form">
      <div className="attr-form cols-3">
        <div className="field">
          <label htmlFor="rl-target">{t("On")}</label>
          <select id="rl-target" value={targetId} onChange={(e) => setTargetId(e.target.value)}>
            {readable.map((a) => (
              <option key={a.id} value={a.id}>
                {a.number} {a.name}
              </option>
            ))}
          </select>
        </div>
        <EditFieldset
          f={f}
          set={setF}
          ops={["ADD", "REMOVE", "QUANTITY", "REPLACE", "SET_ATTRIBUTE"]}
          typeIds={target?.typeId ? [target.typeId] : []}
        />
        <div className="field wide">
          <label htmlFor="rl-note">{t("Note")}</label>
          <input id="rl-note" value={note} onChange={(e) => setNote(e.target.value)} />
        </div>
      </div>
      <ErrorBanner error={add.error} />
      <div className="row" style={{ gap: 8 }}>
        <button className="btn primary small" disabled={add.isPending} onClick={() => add.mutate()}>
          {t("Propose")}
        </button>
        <button className="btn small" onClick={onDone}>
          {t("Cancel")}
        </button>
      </div>
    </div>
  );
}

function MassChangeDialog({ changeId, count, onClose }: { changeId: string; count: number; onClose: () => void }) {
  const [f, setF] = useState<EditFields>(NO_FIELDS);
  const { data: change } = useQuery({
    queryKey: ["change", changeId],
    queryFn: () => unwrap(api.GET("/api/v1/changes/{id}", { params: { path: { id: changeId } } })),
  });
  const typeIds = [...new Set((change?.links ?? []).filter((l) => l.role === "AFFECTED" && l.target.typeId).map((l) => l.target.typeId!))];
  const defs = useAttributeDefinitions(typeIds);
  const run = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/changes/{id}/mass-change", { params: { path: { id: changeId } }, body: body(f, defs) })),
  });
  const result = run.data;
  return (
    <Dialog
      open
      title={t("Mass change")}
      onClose={onClose}
      footer={
        result ? (
          <button className="btn primary" onClick={onClose}>
            {t("Done")}
          </button>
        ) : (
          <>
            <button className="btn" onClick={onClose}>
              {t("Cancel")}
            </button>
            <button className="btn primary" disabled={run.isPending} onClick={() => run.mutate()}>
              {t("Propose on the affected objects")}
            </button>
          </>
        )
      }
    >
      {result ? (
        <div className="mass-result">
          <p>
            <strong>{tn("{n} edit proposed.", "{n} edits proposed.", result.created.length)}</strong>
          </p>
          {result.created.map((r) => (
            <div key={r.id}>
              <ObjectCell o={r.target} />
            </div>
          ))}
          {result.skipped.length > 0 && (
            <>
              <p className="muted" style={{ marginTop: 12 }}>
                {tn("{n} object skipped:", "{n} objects skipped:", result.skipped.length)}
              </p>
              <ul>
                {result.skipped.map((s) => (
                  <li key={s.objectId}>
                    <strong>{s.number ?? t("(no access)")}</strong> — {s.reason}
                  </li>
                ))}
              </ul>
            </>
          )}
        </div>
      ) : (
        <>
          <p className="muted">
            {tn(
              "The edit is proposed on each of the {n} affected object it applies to.",
              "The edit is proposed on each of the {n} affected objects it applies to.",
              count,
            )}
          </p>
          <div className="attr-form">
            <EditFieldset f={f} set={setF} ops={["REPLACE", "REMOVE", "QUANTITY", "SET_ATTRIBUTE"]} typeIds={typeIds} />
          </div>
          <ErrorBanner error={run.error} />
        </>
      )}
    </Dialog>
  );
}
