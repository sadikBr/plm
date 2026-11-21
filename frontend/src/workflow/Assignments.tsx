import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus, Trash2 } from "lucide-react";
import { useState } from "react";
import { api, unwrap, type Schemas } from "../api/client";
import { useContextsOfKind, useLifecycles, useSite, useTypes } from "../api/queries";
import { pickLabel } from "../components/AttributeForm";
import { ErrorBanner, Loading, Panel, typeLabel } from "../components/common";
import { t } from "../i18n/i18n";

type Assignment = Schemas["ProcessAssignment"];
type Trigger = Assignment["trigger"];

/** Process assignments (roadmap 5.5): which process runs for which type, where and when. */

export function triggerLabel(a: Pick<Assignment, "trigger" | "state">, stateName?: (k: string) => string): string {
  switch (a.trigger) {
    case "ON_CREATE":
      return t("when created");
    case "ON_STATE":
      return t("on entering {state}", { state: stateName?.(a.state ?? "") ?? a.state ?? "" });
    case "MANUAL":
      return t("offered to start by hand");
  }
  return a.trigger;
}

export function useStateNames() {
  const { data: lifecycles } = useLifecycles();
  const names = new Map((lifecycles ?? []).flatMap((l) => l.states.map((s) => [s.key, pickLabel(s.labels, s.key)] as const)));
  return (k: string) => names.get(k) ?? k;
}

export function AssignmentsAdmin() {
  const qc = useQueryClient();
  const { data, error, isLoading } = useQuery({
    queryKey: ["processAssignments"],
    queryFn: () => unwrap(api.GET("/api/v1/process-assignments")),
  });
  const { data: types } = useTypes();
  const stateName = useStateNames();
  const remove = useMutation({
    mutationFn: (id: string) => unwrap(api.DELETE("/api/v1/process-assignments/{id}", { params: { path: { id } } })),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["processAssignments"] }),
  });
  const [adding, setAdding] = useState(false);
  return (
    <Panel
      title={t("Where processes run")}
      note={t("Assigned in a context, for a type: applies below it and to subtypes; the nearest context wins.")}
      flush
      actions={
        <button className="btn small" onClick={() => setAdding(true)}>
          <Plus size={13} aria-hidden /> {t("Assign")}
        </button>
      }
    >
      <ErrorBanner error={error ?? remove.error} />
      {adding && <AssignmentForm onDone={() => setAdding(false)} />}
      {isLoading ? (
        <Loading />
      ) : !data?.length ? (
        <div className="empty">{t("No process is assigned yet: people start them by hand.")}</div>
      ) : (
        <table className="grid" aria-label={t("Process assignments")}>
          <thead>
            <tr>
              <th>{t("In")}</th>
              <th>{t("For")}</th>
              <th>{t("Process")}</th>
              <th>{t("Starts")}</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {data.map((a) => (
              <tr key={a.id} className={a.active ? undefined : "muted"}>
                <td>{a.contextName}</td>
                <td>{typeLabel(types?.find((x) => x.id === a.typeId))}</td>
                <td>
                  <strong>{a.templateName}</strong>
                </td>
                <td>{triggerLabel(a, stateName)}</td>
                <td className="num">
                  <button className="icon-btn" aria-label={t("Remove the assignment")} onClick={() => remove.mutate(a.id)}>
                    <Trash2 size={13} />
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Panel>
  );
}

function AssignmentForm({ onDone }: { onDone: () => void }) {
  const qc = useQueryClient();
  const { data: site } = useSite();
  const { data: orgs } = useContextsOfKind("ORGANIZATION");
  const { data: products } = useContextsOfKind("PRODUCT");
  const { data: types } = useTypes();
  const { data: templates } = useQuery({ queryKey: ["processTemplates"], queryFn: () => unwrap(api.GET("/api/v1/process-templates")) });
  const { data: lifecycles } = useLifecycles();
  const states = [
    ...new Map((lifecycles ?? []).flatMap((l) => l.states.map((s) => [s.key, pickLabel(s.labels, s.key)] as const))).entries(),
  ];
  const [contextId, setContextId] = useState("");
  const [typeId, setTypeId] = useState("");
  const [trigger, setTrigger] = useState<Trigger>("ON_CREATE");
  const [state, setState] = useState("UNDER_REVIEW");
  const [templateId, setTemplateId] = useState("");
  const save = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/api/v1/process-assignments", {
          body: {
            contextId: contextId || site!.id,
            typeId: typeId || types!.find((x) => x.instantiable)!.id,
            trigger,
            state: trigger === "ON_STATE" ? state : null,
            templateId: templateId || templates![0]!.id,
            active: true,
          },
        }),
      ),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["processAssignments"] });
      onDone();
    },
  });
  return (
    <form
      className="attr-form cols-3 assignment-form"
      onSubmit={(e) => {
        e.preventDefault();
        save.mutate();
      }}
    >
      <div className="field">
        <label htmlFor="as-context">{t("In")}</label>
        <select id="as-context" value={contextId} onChange={(e) => setContextId(e.target.value)}>
          <option value="">{t("The whole site")}</option>
          {(orgs ?? []).map((c) => (
            <option key={c.id} value={c.id}>
              {c.name}
            </option>
          ))}
          {(products ?? []).map((c) => (
            <option key={c.id} value={c.id}>
              {c.name}
            </option>
          ))}
        </select>
      </div>
      <div className="field">
        <label htmlFor="as-type">{t("For")}</label>
        <select id="as-type" value={typeId} onChange={(e) => setTypeId(e.target.value)}>
          {(types ?? []).map((x) => (
            <option key={x.id} value={x.id}>
              {typeLabel(x)}
            </option>
          ))}
        </select>
      </div>
      <div className="field">
        <label htmlFor="as-template">{t("Process")}</label>
        <select id="as-template" value={templateId} onChange={(e) => setTemplateId(e.target.value)}>
          {(templates ?? []).map((x) => (
            <option key={x.id} value={x.id}>
              {x.name}
            </option>
          ))}
        </select>
      </div>
      <div className="field">
        <label htmlFor="as-trigger">{t("Starts")}</label>
        <select id="as-trigger" value={trigger} onChange={(e) => setTrigger(e.target.value as Trigger)}>
          <option value="ON_CREATE">{t("when created")}</option>
          <option value="ON_STATE">{t("on entering a state")}</option>
          <option value="MANUAL">{t("offered to start by hand")}</option>
        </select>
      </div>
      {trigger === "ON_STATE" && (
        <div className="field">
          <label htmlFor="as-state">{t("State")}</label>
          <select id="as-state" value={state} onChange={(e) => setState(e.target.value)}>
            {states.map(([k, l]) => (
              <option key={k} value={k}>
                {l}
              </option>
            ))}
          </select>
        </div>
      )}
      <div className="field">
        <span className="label-spacer" aria-hidden />
        <div className="row" style={{ gap: 6 }}>
          <button className="btn primary small" type="submit" disabled={save.isPending || !templates?.length}>
            {t("Assign")}
          </button>
          <button className="btn small" type="button" onClick={onDone}>
            {t("Cancel")}
          </button>
        </div>
      </div>
      <div className="wide">
        <ErrorBanner error={save.error} />
      </div>
    </form>
  );
}
