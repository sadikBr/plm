import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { api, unwrap, type Schemas } from "../api/client";
import { useLifecycles, useTypes } from "../api/queries";
import { pickLabel } from "../components/AttributeForm";
import { ErrorBanner, Loading, Panel, typeLabel } from "../components/common";
import { t } from "../i18n/i18n";
import { usePublishSettings } from "../publishing/Publishing";

type Rule = Schemas["PublishRule"];

/** Publishing rules and download watermarks (roadmap 6.7). */
export function PublishingAdmin() {
  const qc = useQueryClient();
  const { data: settings } = usePublishSettings();
  const { data: rules, isLoading } = useQuery({ queryKey: ["publishRules"], queryFn: () => unwrap(api.GET("/api/v1/publish-rules")) });
  const [editing, setEditing] = useState<Rule | "new" | null>(null);
  const toggle = useMutation({
    mutationFn: (watermarkDownloads: boolean) => unwrap(api.PUT("/api/v1/publish-settings", { body: { watermarkDownloads } })),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["publishSettings"] }),
  });
  const remove = useMutation({
    mutationFn: (id: string) => unwrap(api.DELETE("/api/v1/publish-rules/{id}", { params: { path: { id } } })),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["publishRules"] }),
  });
  const { data: types } = useTypes();
  const typeName = (name: string) => {
    const ty = types?.find((x) => x.name === name);
    return ty ? typeLabel(ty) : name;
  };
  if (isLoading) return <Loading />;
  return (
    <div style={{ display: "grid", gap: 16 }}>
      <Panel title={t("Watermarks")} note={t("PDFs handed out by the platform: files, publications and data sheets.")}>
        <label className="check">
          <input type="checkbox" checked={!!settings?.watermarkDownloads} onChange={(e) => toggle.mutate(e.target.checked)} />
          {t("Stamp downloaded PDFs with who downloaded them, when, and the version and state")}
        </label>
        <ErrorBanner error={toggle.error} />
      </Panel>
      <Panel
        title={t("Publishing rules")}
        note={t("Which types publish a PDF, and when: the version's PDF stamped with number, version and state, or a data sheet.")}
        actions={
          <button className="btn small" onClick={() => setEditing("new")}>
            {t("+ Rule")}
          </button>
        }
      >
        {editing && (
          <RuleForm
            key={editing === "new" ? "new" : editing.id}
            rule={editing === "new" ? null : editing}
            onDone={() => setEditing(null)}
          />
        )}
        <ErrorBanner error={remove.error} />
        {!rules?.length ? (
          <p className="empty">{t("No rules: nothing is published by itself.")}</p>
        ) : (
          <table className="grid">
            <thead>
              <tr>
                <th>{t("Name")}</th>
                <th>{t("Types")}</th>
                <th>{t("When")}</th>
                <th>{t("Watermark")}</th>
                <th>{t("On")}</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {rules.map((r) => (
                <tr key={r.id}>
                  <td>
                    <strong>{r.name}</strong>
                  </td>
                  <td>{r.types.length ? r.types.map(typeName).join(", ") : t("all")}</td>
                  <td>{r.trigger === "CHECK_IN" ? t("on check-in") : t("on reaching {state}", { state: r.state ?? "" })}</td>
                  <td>{r.watermark ? t("Yes") : t("No")}</td>
                  <td>{r.active ? t("Yes") : t("No")}</td>
                  <td className="row-actions">
                    <button className="btn ghost small" onClick={() => setEditing(r)}>
                      {t("Edit")}
                    </button>
                    <button className="btn ghost small" onClick={() => remove.mutate(r.id)}>
                      {t("Delete")}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Panel>
    </div>
  );
}

function RuleForm({ rule, onDone }: { rule: Rule | null; onDone: () => void }) {
  const qc = useQueryClient();
  const { data: types } = useTypes();
  const { data: lifecycles } = useLifecycles();
  const [name, setName] = useState(rule?.name ?? "");
  const [trigger, setTrigger] = useState<Rule["trigger"]>(rule?.trigger ?? "STATE");
  const [state, setState] = useState(rule?.state ?? "RELEASED");
  const [typeNames, setTypeNames] = useState<string[]>(rule?.types ?? []);
  const [watermark, setWatermark] = useState(rule?.watermark ?? true);
  const [active, setActive] = useState(rule?.active ?? true);
  const save = useMutation({
    mutationFn: () => {
      const body = { name, trigger, state: trigger === "STATE" ? state : null, types: typeNames, watermark, active };
      return rule
        ? unwrap(api.PUT("/api/v1/publish-rules/{id}", { params: { path: { id: rule.id } }, body }))
        : unwrap(api.POST("/api/v1/publish-rules", { body }));
    },
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["publishRules"] });
      onDone();
    },
  });
  const states = new Map<string, string>();
  for (const lc of lifecycles ?? []) for (const s of lc.states) if (!states.has(s.key)) states.set(s.key, pickLabel(s.labels, s.key));
  const toggle = (n: string) => setTypeNames(typeNames.includes(n) ? typeNames.filter((x) => x !== n) : [...typeNames, n]);
  return (
    <div className="inline-form">
      <div className="attr-form cols-3">
        <div className="field">
          <label htmlFor="pr-name">{t("Name")}</label>
          <input id="pr-name" value={name} onChange={(e) => setName(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="pr-trigger">{t("When")}</label>
          <select id="pr-trigger" value={trigger} onChange={(e) => setTrigger(e.target.value as Rule["trigger"])}>
            <option value="STATE">{t("on reaching a state")}</option>
            <option value="CHECK_IN">{t("on check-in")}</option>
          </select>
        </div>
        {trigger === "STATE" && (
          <div className="field">
            <label htmlFor="pr-state">{t("State")}</label>
            <select id="pr-state" value={state} onChange={(e) => setState(e.target.value)}>
              {[...states.entries()].map(([k, label]) => (
                <option key={k} value={k}>
                  {label}
                </option>
              ))}
            </select>
          </div>
        )}
      </div>
      <fieldset className="check-list scroll-list">
        <legend>{t("For types (none: all)")}</legend>
        {(types ?? [])
          .filter((x) => x.baseClass === "PART" || x.baseClass === "DOCUMENT")
          .sort((a, b) => Number(typeNames.includes(b.name)) - Number(typeNames.includes(a.name)))
          .map((x) => (
            <label key={x.id} className="check">
              <input type="checkbox" checked={typeNames.includes(x.name)} onChange={() => toggle(x.name)} />
              {typeLabel(x)}
            </label>
          ))}
      </fieldset>
      <div className="row" style={{ gap: 12 }}>
        <label className="check">
          <input type="checkbox" checked={watermark} onChange={(e) => setWatermark(e.target.checked)} />
          {t("Stamp the state across the pages")}
        </label>
        <label className="check">
          <input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} />
          {t("On")}
        </label>
        <button className="btn primary small" disabled={!name.trim() || save.isPending} onClick={() => save.mutate()}>
          {t("Save")}
        </button>
        <button className="btn small" onClick={onDone}>
          {t("Cancel")}
        </button>
      </div>
      <ErrorBanner error={save.error} />
    </div>
  );
}
