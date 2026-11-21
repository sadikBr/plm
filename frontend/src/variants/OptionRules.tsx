import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckCircle2, CircleAlert, Plus, Trash2 } from "lucide-react";
import { useState } from "react";
import { api, unwrap, type Schemas } from "../api/client";
import { ErrorBanner } from "../components/common";
import { t } from "../i18n/i18n";

type OptionSet = Schemas["OptionSet"];
type Ref = Schemas["ChoiceRef"];
type Kind = Schemas["SaveRule"]["kind"];

const refKey = (r: Ref) => `${r.option}=${r.choice}`;
const parse = (k: string): Ref => ({ option: k.split("=")[0]!, choice: k.split("=")[1]! });

export const useRules = (setId: string) =>
  useQuery({
    queryKey: ["optionRules", setId],
    queryFn: () => unwrap(api.GET("/api/v1/option-sets/{id}/rules", { params: { path: { id: setId } } })),
  });

/** Every choice of the set, labelled `MOTOR=HT High torque`. */
function ChoiceOptions({ set, exclude }: { set: OptionSet; exclude?: string }) {
  return (
    <>
      {set.options.map((o) => (
        <optgroup key={o.id} label={`${o.code} · ${o.name}`}>
          {o.choices
            .filter((c) => !c.retired && `${o.code}=${c.code}` !== exclude)
            .map((c) => (
              <option key={c.id} value={`${o.code}=${c.code}`}>
                {o.code}={c.code} · {c.name}
              </option>
            ))}
        </optgroup>
      ))}
    </>
  );
}

/** A set's rules (roadmap 7.6): what a choice includes, requires or excludes. */
export function RulesSection({ set, canEdit }: { set: OptionSet; canEdit: boolean }) {
  const qc = useQueryClient();
  const { data: rules } = useRules(set.id);
  const [kind, setKind] = useState<Kind>("REQUIRES");
  const [ifKey, setIf] = useState("");
  const [thens, setThens] = useState<string[]>([]);
  const save = (r: Schemas["OptionRule"][]) => qc.setQueryData(["optionRules", set.id], r);
  const add = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/api/v1/option-sets/{id}/rules", {
          params: { path: { id: set.id } },
          body: { kind, ifChoice: parse(ifKey), thenChoices: thens.map(parse) },
        }),
      ),
    onSuccess: (r) => {
      save(r);
      setThens([]);
    },
  });
  const remove = useMutation({
    mutationFn: (ruleId: string) => unwrap(api.DELETE("/api/v1/option-rules/{ruleId}", { params: { path: { ruleId } } })),
    onSuccess: save,
  });
  return (
    <div className="rules-section">
      <h4>{t("Rules")}</h4>
      <ErrorBanner error={add.error ?? remove.error} />
      {rules?.length ? (
        <ul className="rule-list" aria-label={t("Rules of {name}", { name: set.name })}>
          {rules.map((r) => (
            <li key={r.id}>
              <span className={`tag rule-${r.kind.toLowerCase()}`}>
                {r.kind === "INCLUDES" ? t("includes") : r.kind === "REQUIRES" ? t("requires") : t("excludes")}
              </span>{" "}
              <code>{r.text}</code>
              {r.note && <span className="muted small"> · {r.note}</span>}
              {canEdit && (
                <button className="icon-btn" aria-label={t("Remove the rule")} onClick={() => remove.mutate(r.id)}>
                  <Trash2 size={12} />
                </button>
              )}
            </li>
          ))}
        </ul>
      ) : (
        <p className="muted small">{t("No rules: any combination of choices goes.")}</p>
      )}
      {canEdit && (
        <div className="row wrap rule-form">
          <label className="field">
            <span>{t("When")}</span>
            <select value={ifKey} onChange={(e) => setIf(e.target.value)}>
              <option value="">{t("Choose…")}</option>
              <ChoiceOptions set={set} />
            </select>
          </label>
          <label className="field">
            <span>{t("it")}</span>
            <select value={kind} onChange={(e) => setKind(e.target.value as Kind)}>
              <option value="REQUIRES">{t("requires one of")}</option>
              <option value="INCLUDES">{t("includes")}</option>
              <option value="EXCLUDES">{t("excludes")}</option>
            </select>
          </label>
          <label className="field grow">
            <span>{t("These choices (Ctrl-click for several)")}</span>
            <select multiple size={4} value={thens} onChange={(e) => setThens([...e.target.selectedOptions].map((o) => o.value))}>
              <ChoiceOptions set={set} exclude={ifKey} />
            </select>
          </label>
          <button className="btn small" disabled={!ifKey || !thens.length || add.isPending} onClick={() => add.mutate()}>
            <Plus size={13} aria-hidden /> {t("Rule")}
          </button>
        </div>
      )}
      <SelectionChecker set={set} />
    </div>
  );
}

/** Picks a choice per option and says what the rules think of it (roadmap 7.6). */
export function SelectionChecker({
  set,
  value,
  onChange,
}: {
  set: OptionSet;
  value?: Record<string, string>;
  onChange?: (s: Record<string, string>) => void;
}) {
  const [own, setOwn] = useState<Record<string, string>>({});
  const selection = value ?? own;
  const change = onChange ?? setOwn;
  const { data: check } = useQuery({
    queryKey: ["optionCheck", set.id, selection],
    queryFn: () => unwrap(api.POST("/api/v1/option-sets/{id}/check", { params: { path: { id: set.id } }, body: { selection } })),
    enabled: Object.keys(selection).length > 0,
  });
  const apply = (fix: Ref) => change({ ...selection, [fix.option]: fix.choice });
  const drop = (r: Ref) => {
    const next = { ...selection };
    delete next[r.option];
    change(next);
  };
  return (
    <div className="selection-checker">
      <h4>{value ? t("Choices") : t("Check a selection")}</h4>
      <div className="row wrap">
        {set.options.map((o) => (
          <label key={o.id} className="field">
            <span>
              {o.name} {!o.required && <span className="muted">({t("optional")})</span>}
            </span>
            <select
              aria-label={o.name}
              value={selection[o.code] ?? ""}
              onChange={(e) => change({ ...selection, [o.code]: e.target.value })}
            >
              <option value="">{o.required ? t("Choose…") : t("None")}</option>
              {o.choices
                .filter((c) => !c.retired || selection[o.code] === c.code)
                .map((c) => (
                  <option key={c.id} value={c.code}>
                    {c.name}
                  </option>
                ))}
            </select>
          </label>
        ))}
      </div>
      {check &&
        (check.valid ? (
          <p className="check-ok">
            <CheckCircle2 size={14} aria-hidden /> {t("A valid selection.")}
          </p>
        ) : (
          <ul className="check-problems" aria-label={t("Problems")}>
            {check.problems.map((p, i) => (
              <li key={i}>
                <CircleAlert size={14} aria-hidden /> {p.message}
                {p.fixes.length > 0 && (
                  <span className="fixes">
                    {p.fixes.map((f) => (
                      <button key={refKey(f)} className="btn small ghost" onClick={() => apply(f)}>
                        {t("Choose {choice}", { choice: refKey(f) })}
                      </button>
                    ))}
                  </span>
                )}
                {p.drop.length > 0 && (
                  <span className="fixes">
                    {p.drop.map((f) => (
                      <button key={refKey(f)} className="btn small ghost" onClick={() => drop(f)}>
                        {t("Drop {choice}", { choice: refKey(f) })}
                      </button>
                    ))}
                  </span>
                )}
              </li>
            ))}
          </ul>
        ))}
    </div>
  );
}
