import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { api, unwrap, type Schemas } from "../api/client";
import { useTypes } from "../api/queries";
import { ErrorBanner, Loading, Panel, typeLabel } from "../components/common";
import { roleLabel } from "../changes/Changes";
import { t } from "../i18n/i18n";

type Template = Schemas["ChangeTemplate"];
type Role = Schemas["AssociationRule"]["role"];
const ROLES: Role[] = ["AFFECTED", "RESULTING", "END_ITEM", "ADDRESSES", "IMPLEMENTS", "TASK"];

/** Change templates and association rules (roadmap 5.10). */
export function ChangesAdmin() {
  return (
    <div style={{ display: "grid", gap: 16 }}>
      <ReleaseGatePanel />
      <ChangeTemplatesPanel />
      <AssociationRulesPanel />
    </div>
  );
}

function useChangeTypes() {
  const { data: types } = useTypes();
  const all = types ?? [];
  return { all, changeTypes: all.filter((x) => x.baseClass === "CHANGE" && x.instantiable) };
}

function ChangeTemplatesPanel() {
  const qc = useQueryClient();
  const { data: templates, isLoading } = useQuery({
    queryKey: ["changeTemplates"],
    queryFn: () => unwrap(api.GET("/api/v1/change-templates")),
  });
  const [editing, setEditing] = useState<Template | "new" | null>(null);
  const remove = useMutation({
    mutationFn: (id: string) => unwrap(api.DELETE("/api/v1/change-templates/{id}", { params: { path: { id } } })),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["changeTemplates"] }),
  });
  if (isLoading) return <Loading />;
  return (
    <Panel
      title={t("Change templates")}
      note={t("Prefilled changes: a type, a title, a description and the tasks created with it.")}
      actions={
        <button className="btn small" onClick={() => setEditing("new")}>
          {t("+ Template")}
        </button>
      }
    >
      <ErrorBanner error={remove.error} />
      {editing && (
        <TemplateForm
          key={editing === "new" ? "new" : editing.id}
          template={editing === "new" ? null : editing}
          onDone={() => setEditing(null)}
        />
      )}
      {templates?.length ? (
        <table className="grid">
          <thead>
            <tr>
              <th>{t("Name")}</th>
              <th>{t("Type")}</th>
              <th>{t("Tasks")}</th>
              <th>{t("Active")}</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {templates.map((x) => (
              <tr key={x.id}>
                <td>
                  <strong>{x.name}</strong>
                  {x.description && <div className="muted">{x.description}</div>}
                </td>
                <td>
                  <TypeName id={x.typeId} />
                </td>
                <td>{x.tasks.join(", ") || "—"}</td>
                <td>{x.active ? t("Yes") : t("No")}</td>
                <td className="row-actions">
                  <button className="btn ghost small" onClick={() => setEditing(x)}>
                    {t("Edit")}
                  </button>
                  <button className="btn ghost small" onClick={() => remove.mutate(x.id)}>
                    {t("Delete")}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <p className="empty">{t("No change templates yet.")}</p>
      )}
    </Panel>
  );
}

function TypeName({ id }: { id: string }) {
  const { all } = useChangeTypes();
  return <>{typeLabel(all.find((x) => x.id === id))}</>;
}

function TemplateForm({ template, onDone }: { template: Template | null; onDone: () => void }) {
  const qc = useQueryClient();
  const { changeTypes } = useChangeTypes();
  const [name, setName] = useState(template?.name ?? "");
  const [typeId, setTypeId] = useState(template?.typeId ?? "");
  const [title, setTitle] = useState(template?.title ?? "");
  const [description, setDescription] = useState(template?.description ?? "");
  const [tasks, setTasks] = useState(template?.tasks.join("\n") ?? "");
  const [active, setActive] = useState(template?.active ?? true);
  const save = useMutation({
    mutationFn: () => {
      const body = {
        name: name.trim(),
        typeId: typeId || changeTypes[0]!.id,
        title: title.trim() || null,
        description: description.trim() || null,
        tasks: tasks
          .split("\n")
          .map((x) => x.trim())
          .filter(Boolean),
        active,
      };
      return template
        ? unwrap(api.PUT("/api/v1/change-templates/{id}", { params: { path: { id: template.id } }, body }))
        : unwrap(api.POST("/api/v1/change-templates", { body }));
    },
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["changeTemplates"] });
      onDone();
    },
  });
  return (
    <div className="inline-form">
      <div className="attr-form cols-3">
        <div className="field">
          <label htmlFor="ct-name">{t("Name")}</label>
          <input id="ct-name" value={name} onChange={(e) => setName(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="ct-type">{t("Type")}</label>
          <select id="ct-type" value={typeId || changeTypes[0]?.id || ""} onChange={(e) => setTypeId(e.target.value)}>
            {changeTypes.map((x) => (
              <option key={x.id} value={x.id}>
                {typeLabel(x)}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="ct-title">{t("Default title")}</label>
          <input id="ct-title" value={title} onChange={(e) => setTitle(e.target.value)} />
        </div>
        <div className="field wide">
          <label htmlFor="ct-desc">{t("Description")}</label>
          <input id="ct-desc" value={description} onChange={(e) => setDescription(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="ct-tasks">{t("Tasks (one per line)")}</label>
          <textarea id="ct-tasks" rows={3} value={tasks} onChange={(e) => setTasks(e.target.value)} />
        </div>
        <div className="field">
          <label className="check">
            <input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} />
            {t("Offered when creating changes")}
          </label>
        </div>
      </div>
      <ErrorBanner error={save.error} />
      <div className="row" style={{ gap: 8 }}>
        <button className="btn primary small" disabled={!name.trim() || save.isPending} onClick={() => save.mutate()}>
          {t("Save")}
        </button>
        <button className="btn small" onClick={onDone}>
          {t("Cancel")}
        </button>
      </div>
    </div>
  );
}

function AssociationRulesPanel() {
  const qc = useQueryClient();
  const { all, changeTypes } = useChangeTypes();
  const { data: rules, isLoading } = useQuery({
    queryKey: ["associationRules"],
    queryFn: () => unwrap(api.GET("/api/v1/change-association-rules")),
  });
  const [changeTypeId, setChangeTypeId] = useState("");
  const [role, setRole] = useState<Role>("AFFECTED");
  const [targetTypeId, setTargetTypeId] = useState("");
  const targets = all.filter((x) => x.baseClass !== "CONTEXT");
  const refresh = () => qc.invalidateQueries({ queryKey: ["associationRules"] });
  const add = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/api/v1/change-association-rules", {
          body: { changeTypeId: changeTypeId || changeTypes[0]!.id, role, targetTypeId: targetTypeId || targets[0]!.id },
        }),
      ),
    onSuccess: refresh,
  });
  const remove = useMutation({
    mutationFn: (id: string) => unwrap(api.DELETE("/api/v1/change-association-rules/{id}", { params: { path: { id } } })),
    onSuccess: refresh,
  });
  if (isLoading) return <Loading />;
  const name = (id: string) => typeLabel(all.find((x) => x.id === id));
  return (
    <Panel
      title={t("Association rules")}
      note={t("Which types each change type may link, per role. A change type and role with no rule allow any type.")}
    >
      <ErrorBanner error={add.error ?? remove.error} />
      {rules?.length ? (
        <table className="grid">
          <thead>
            <tr>
              <th>{t("Change type")}</th>
              <th>{t("Role")}</th>
              <th>{t("May link")}</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {rules.map((r) => (
              <tr key={r.id}>
                <td>{name(r.changeTypeId)}</td>
                <td>{roleLabel(r.role)}</td>
                <td>{name(r.targetTypeId)}</td>
                <td className="row-actions">
                  <button className="btn ghost small" onClick={() => remove.mutate(r.id)}>
                    {t("Remove")}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <p className="empty">{t("No rules: changes may link any type.")}</p>
      )}
      <div className="row" style={{ gap: 8, marginTop: 12, flexWrap: "wrap" }}>
        <select
          style={{ width: "auto" }}
          aria-label={t("Change type")}
          value={changeTypeId || changeTypes[0]?.id || ""}
          onChange={(e) => setChangeTypeId(e.target.value)}
        >
          {changeTypes.map((x) => (
            <option key={x.id} value={x.id}>
              {typeLabel(x)}
            </option>
          ))}
        </select>
        <select style={{ width: "auto" }} aria-label={t("Role")} value={role} onChange={(e) => setRole(e.target.value as Role)}>
          {ROLES.map((r) => (
            <option key={r} value={r}>
              {roleLabel(r)}
            </option>
          ))}
        </select>
        <select
          style={{ width: "auto" }}
          aria-label={t("May link")}
          value={targetTypeId || targets[0]?.id || ""}
          onChange={(e) => setTargetTypeId(e.target.value)}
        >
          {targets.map((x) => (
            <option key={x.id} value={x.id}>
              {typeLabel(x)}
            </option>
          ))}
        </select>
        <button className="btn small" disabled={add.isPending || !changeTypes.length} onClick={() => add.mutate()}>
          {t("Add the rule")}
        </button>
      </div>
    </Panel>
  );
}

/** The release gate (roadmap 5.13). */
function ReleaseGatePanel() {
  const { data } = useQuery({ queryKey: ["releaseGate"], queryFn: () => unwrap(api.GET("/api/v1/release-gate")) });
  if (!data) return <Loading />;
  return <ReleaseGateForm key={data.updatedAt ?? "default"} gate={data} />;
}

function ReleaseGateForm({ gate }: { gate: Schemas["ReleaseGate"] }) {
  const qc = useQueryClient();
  const [enabled, setEnabled] = useState(gate.enabled);
  const [states, setStates] = useState(gate.states.join(", "));
  const save = useMutation({
    mutationFn: () =>
      unwrap(
        api.PUT("/api/v1/release-gate", {
          body: {
            enabled,
            states: states
              .split(",")
              .map((x) => x.trim())
              .filter(Boolean),
          },
        }),
      ),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["releaseGate"] }),
  });
  return (
    <Panel
      title={t("Release gate")}
      note={t("Parts and documents reach these states only when a change notice is resolved or a promotion request approved.")}
    >
      <div className="row" style={{ gap: 16, flexWrap: "wrap", alignItems: "flex-end" }}>
        <label className="check">
          <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
          {t("On")}
        </label>
        <div className="field" style={{ minWidth: 260 }}>
          <label htmlFor="gate-states">{t("Gated states (keys, comma-separated)")}</label>
          <input id="gate-states" value={states} disabled={!enabled} onChange={(e) => setStates(e.target.value)} />
        </div>
        <button className="btn primary small" disabled={save.isPending} onClick={() => save.mutate()}>
          {t("Save")}
        </button>
        {save.isSuccess && <span className="muted">{t("Saved.")}</span>}
      </div>
      <p className="hint" style={{ marginBottom: 0 }}>
        {t("Site administrators can still override, and the override is recorded in the object's history and the audit trail.")}
        {gate.updatedBy && <> {t("Last changed by {who}.", { who: gate.updatedBy })}</>}
      </p>
      <ErrorBanner error={save.error} />
    </Panel>
  );
}
