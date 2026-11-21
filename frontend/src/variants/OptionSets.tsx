import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus, SlidersHorizontal } from "lucide-react";
import { useState } from "react";
import { api, unwrap, type Schemas } from "../api/client";
import { usePermissions, type VersionedObject } from "../api/queries";
import { ErrorBanner, Loading, Panel } from "../components/common";
import { t, tn } from "../i18n/i18n";
import { RulesSection } from "./OptionRules";
import { ConfiguredStructurePanel } from "./Configured";
import { VariantSpecsPanel } from "./VariantSpecs";

type OptionSet = Schemas["OptionSet"];

export const useOptionSets = (contextId?: string) =>
  useQuery({
    queryKey: ["optionSets", contextId ?? "all"],
    queryFn: () => unwrap(api.GET("/api/v1/option-sets", { params: { query: contextId ? { contextId } : {} } })),
  });

/** A small inline form: a code and a name, and what to do with them. */
function CodeNameForm({
  label,
  onSave,
  busy,
  extra,
}: {
  label: string;
  onSave: (code: string, name: string) => void;
  busy: boolean;
  extra?: React.ReactNode;
}) {
  const [code, setCode] = useState("");
  const [name, setName] = useState("");
  return (
    <form
      className="row option-form"
      onSubmit={(e) => {
        e.preventDefault();
        onSave(code, name);
        setCode("");
        setName("");
      }}
    >
      <input
        aria-label={t("{what} code", { what: label })}
        placeholder={t("CODE")}
        value={code}
        onChange={(e) => setCode(e.target.value.toUpperCase())}
        className="code-input"
      />
      <input
        aria-label={t("{what} name", { what: label })}
        placeholder={t("Name")}
        value={name}
        onChange={(e) => setName(e.target.value)}
      />
      {extra}
      <button className="btn small" disabled={busy || !code.trim() || !name.trim()}>
        <Plus size={13} aria-hidden /> {label}
      </button>
    </form>
  );
}

/** One option set: its options as rows, their choices as chips (roadmap 7.5). */
export function OptionSetCard({ set, canEdit, children }: { set: OptionSet; canEdit: boolean; children?: React.ReactNode }) {
  const qc = useQueryClient();
  const refresh = (s: OptionSet) => {
    qc.setQueryData(["optionSet", s.id], s);
    void qc.invalidateQueries({ queryKey: ["optionSets"] });
    void qc.invalidateQueries({ queryKey: ["partOptions"] });
  };
  const addOption = useMutation({
    mutationFn: (b: { code: string; name: string; required: boolean }) =>
      unwrap(api.POST("/api/v1/option-sets/{id}/options", { params: { path: { id: set.id } }, body: b })),
    onSuccess: refresh,
  });
  const addChoice = useMutation({
    mutationFn: (b: { optionId: string; code: string; name: string }) =>
      unwrap(
        api.POST("/api/v1/options/{optionId}/choices", {
          params: { path: { optionId: b.optionId } },
          body: { code: b.code, name: b.name, retired: false },
        }),
      ),
    onSuccess: refresh,
  });
  const retire = useMutation({
    mutationFn: (c: Schemas["Choice"]) =>
      unwrap(
        api.PUT("/api/v1/choices/{choiceId}", {
          params: { path: { choiceId: c.id } },
          body: { code: c.code, name: c.name, retired: !c.retired },
        }),
      ),
    onSuccess: refresh,
  });
  const [required, setRequired] = useState(true);
  return (
    <Panel
      title={set.name}
      note={[
        set.description,
        set.usedBy ? tn("used by {n} configurable part", "used by {n} configurable parts", set.usedBy) : t("not used yet"),
      ]
        .filter(Boolean)
        .join(" · ")}
      flush
    >
      <ErrorBanner error={addOption.error ?? addChoice.error ?? retire.error} />
      <table className="grid options-table" aria-label={t("Options of {name}", { name: set.name })}>
        <thead>
          <tr>
            <th>{t("Option")}</th>
            <th>{t("Choices")}</th>
          </tr>
        </thead>
        <tbody>
          {set.options.map((o) => (
            <tr key={o.id}>
              <td className="nowrap">
                <code>{o.code}</code> {o.name}
                {!o.required && <span className="muted small"> · {t("optional")}</span>}
              </td>
              <td>
                <div className="choice-chips">
                  {o.choices.map((c) => (
                    <button
                      key={c.id}
                      className={`chip ${c.retired ? "retired" : ""}`}
                      disabled={!canEdit}
                      title={canEdit ? (c.retired ? t("Retired: click to bring it back") : t("Click to retire it")) : undefined}
                      onClick={() => retire.mutate(c)}
                    >
                      <code>{c.code}</code> {c.name}
                    </button>
                  ))}
                  {canEdit && (
                    <CodeNameForm
                      label={t("Choice")}
                      busy={addChoice.isPending}
                      onSave={(code, name) => addChoice.mutate({ optionId: o.id, code, name })}
                    />
                  )}
                </div>
              </td>
            </tr>
          ))}
          {!set.options.length && (
            <tr>
              <td colSpan={2} className="empty">
                {t("No options yet.")}
              </td>
            </tr>
          )}
        </tbody>
      </table>
      {canEdit && (
        <div className="option-add">
          <CodeNameForm
            label={t("Option")}
            busy={addOption.isPending}
            onSave={(code, name) => addOption.mutate({ code, name, required })}
            extra={
              <label className="check">
                <input type="checkbox" checked={required} onChange={(e) => setRequired(e.target.checked)} /> {t("Required")}
              </label>
            }
          />
        </div>
      )}
      {children}
    </Panel>
  );
}

/** A library's or product's option sets (roadmap 7.5). */
export function OptionSetsTab({ contextId, canEdit }: { contextId: string; canEdit: boolean }) {
  const qc = useQueryClient();
  const { data, error } = useOptionSets(contextId);
  const [name, setName] = useState("");
  const create = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/option-sets", { body: { contextId, name } })),
    onSuccess: () => {
      setName("");
      void qc.invalidateQueries({ queryKey: ["optionSets"] });
    },
  });
  if (!data) return error ? <ErrorBanner error={error} /> : <Loading />;
  return (
    <>
      <p className="muted">
        {t(
          "Option sets hold the options a configurable product is ordered with, and their choices. Parts using them get variants built from their 150% structure.",
        )}
      </p>
      <ErrorBanner error={create.error} />
      {data.map((s) => (
        <OptionSetCard key={s.id} set={s} canEdit={canEdit}>
          <RulesSection set={s} canEdit={canEdit} />
        </OptionSetCard>
      ))}
      {!data.length && <div className="empty">{t("No option sets here yet.")}</div>}
      {canEdit && (
        <form
          className="row"
          onSubmit={(e) => {
            e.preventDefault();
            create.mutate();
          }}
        >
          <input
            aria-label={t("New option set")}
            placeholder={t("New option set, e.g. Rover options")}
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
          <button className="btn primary" disabled={!name.trim() || create.isPending}>
            <Plus size={14} aria-hidden /> {t("Create")}
          </button>
        </form>
      )}
    </>
  );
}

export const usePartOptions = (partId: string) =>
  useQuery({
    queryKey: ["partOptions", partId],
    queryFn: () => unwrap(api.GET("/api/v1/objects/{id}/options", { params: { path: { id: partId } } })),
  });

/** A part's options (roadmap 7.5): which set makes it configurable. */
export function PartOptionsPanel({ obj }: { obj: VersionedObject }) {
  const qc = useQueryClient();
  const { data: permissions } = usePermissions(obj.contextId, obj.typeId);
  const canEdit = !!permissions?.has("MODIFY");
  const [selection, setSelection] = useState<Record<string, string>>({});
  const [specId, setSpecId] = useState<string>();
  const { data } = usePartOptions(obj.id);
  const { data: sets } = useOptionSets();
  const assign = useMutation({
    mutationFn: (optionSetId: string | null) =>
      unwrap(api.PUT("/api/v1/objects/{id}/options", { params: { path: { id: obj.id } }, body: { optionSetId } })),
    onSuccess: (r) => {
      qc.setQueryData(["partOptions", obj.id], r);
      void qc.invalidateQueries({ queryKey: ["optionSets"] });
    },
  });
  if (!data) return <Loading />;
  const set = data.optionSet;
  return (
    <>
      <ErrorBanner error={assign.error} />
      <Panel
        title={t("Configurable")}
        note={
          set
            ? t("{number} is configured with {set}.", { number: obj.number, set: set.name })
            : t("{number} has no options: it's built one way.", { number: obj.number })
        }
        actions={
          canEdit && (
            <select
              aria-label={t("Option set")}
              style={{ width: 260 }}
              value={set?.id ?? ""}
              onChange={(e) => assign.mutate(e.target.value || null)}
            >
              <option value="">{t("Not configurable")}</option>
              {sets?.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
          )
        }
      >
        {!set && (
          <p className="muted small" style={{ margin: 0 }}>
            <SlidersHorizontal size={13} aria-hidden />{" "}
            {t("Choose an option set to give its structure lines conditions and build variants of it.")}
          </p>
        )}
      </Panel>
      {set && (
        <VariantSpecsPanel
          obj={obj}
          set={set}
          selection={selection}
          current={specId}
          onOpen={(sp) => {
            setSelection(sp.selection);
            setSpecId(sp.id);
          }}
        />
      )}
      {set && (
        <ConfiguredStructurePanel
          obj={obj}
          set={set}
          selection={selection}
          setSelection={(s) => {
            setSelection(s);
            setSpecId(undefined);
          }}
        />
      )}
      {set && <OptionSetCard set={set} canEdit={false} />}
    </>
  );
}
