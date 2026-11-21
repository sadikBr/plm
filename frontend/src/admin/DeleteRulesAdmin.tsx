import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { api, unwrap } from "../api/client";
import { pickLabel } from "../components/AttributeForm";
import { ErrorBanner, Loading, Panel } from "../components/common";
import { useLifecycles } from "../api/queries";
import { t } from "../i18n/i18n";

type Who = "OWNER" | "MANAGERS";

/** Delete rules (roadmap 3.4): in which states objects may be deleted, and by whom. */
export function DeleteRulesAdmin() {
  const { data: rules, isLoading } = useQuery({ queryKey: ["deleteRules"], queryFn: () => unwrap(api.GET("/api/v1/delete-rules")) });
  if (isLoading || !rules) return <Loading />;
  return <DeleteRulesForm initialStates={rules.states} initialWho={rules.who as Who[]} />;
}

function DeleteRulesForm({ initialStates, initialWho }: { initialStates: string[]; initialWho: Who[] }) {
  const qc = useQueryClient();
  const { data: lifecycles } = useLifecycles();
  const [states, setStates] = useState<string[]>(initialStates);
  const [who, setWho] = useState<Who[]>(initialWho);
  const save = useMutation({
    mutationFn: () => unwrap(api.PUT("/api/v1/delete-rules", { body: { states, who } })),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["deleteRules"] }),
  });
  const allStates = new Map<string, string>();
  for (const lc of lifecycles ?? []) for (const s of lc.states) if (!allStates.has(s.key)) allStates.set(s.key, pickLabel(s.labels, s.key));
  const toggle = <T,>(list: T[], set: (v: T[]) => void, v: T) => set(list.includes(v) ? list.filter((x) => x !== v) : [...list, v]);

  return (
    <Panel
      title={t("Delete rules")}
      note={t("Site administrators may always delete. Nothing is deleted while checked out; extension rules can refuse too")}
    >
      <div style={{ display: "grid", gap: 14, maxWidth: 760 }}>
        <ErrorBanner error={save.error} />
        <fieldset className="check-list">
          <legend>{t("Objects may be deleted in these states")}</legend>
          {[...allStates.entries()].map(([key, label]) => (
            <label key={key} className="check">
              <input type="checkbox" checked={states.includes(key)} onChange={() => toggle(states, setStates, key)} />
              {label}
            </label>
          ))}
          <span className="hint check-list-title">{t("None ticked: in any state.")}</span>
        </fieldset>
        <fieldset className="check-list">
          <legend>{t("By")}</legend>
          <label className="check">
            <input type="checkbox" checked={who.includes("OWNER")} onChange={() => toggle(who, setWho, "OWNER")} />
            {t("The object's owner")}
          </label>
          <label className="check">
            <input type="checkbox" checked={who.includes("MANAGERS")} onChange={() => toggle(who, setWho, "MANAGERS")} />
            {t("Managers of its context")}
          </label>
        </fieldset>
        <div className="row" style={{ gap: 8 }}>
          <button className="btn primary" disabled={save.isPending} onClick={() => save.mutate()}>
            {t("Save rules")}
          </button>
          {save.isSuccess && <span className="muted">{t("Saved.")}</span>}
        </div>
      </div>
    </Panel>
  );
}
