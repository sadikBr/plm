import { Download, FileUp, Upload } from "lucide-react";
import { useRef, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api, unwrap, type Schemas } from "../api/client";
import { ErrorBanner, Panel } from "../components/common";
import { t, tn } from "../i18n/i18n";

type Bundle = Schemas["ConfigBundle"];
type Plan = Schemas["ImportPlanResponse"];
type Action = Plan["changes"][number]["action"];

const ACTION_LABEL = (): Record<Action, string> => ({
  CREATE: t("Create"),
  UPDATE: t("Update"),
  UNCHANGED: t("Unchanged"),
  CONFLICT: t("Conflict"),
});

const KIND_LABEL = (): Record<string, string> => ({
  teamRole: t("Team role"),
  enumeration: t("Value list"),
  numberingScheme: t("Numbering scheme"),
  lifecycle: t("Lifecycle"),
  type: t("Type"),
  attribute: t("Attribute"),
  typeRules: t("Type rules"),
  typeLifecycle: t("Type lifecycle"),
  layout: t("Layout"),
  contextTemplate: t("Context template"),
});

/** Export the configuration as a file; import one, review what it would change, then apply. */
export function ConfigurationAdmin() {
  const qc = useQueryClient();
  const fileInput = useRef<HTMLInputElement>(null);
  const [bundle, setBundle] = useState<{ name: string; content: Bundle }>();
  const [readError, setReadError] = useState<string>();
  const [showUnchanged, setShowUnchanged] = useState(false);

  const exportConfig = useMutation({
    mutationFn: () => unwrap(api.GET("/api/v1/configuration/export")),
    onSuccess: (content) => {
      const url = URL.createObjectURL(new Blob([JSON.stringify(content, null, 2) + "\n"], { type: "application/json" }));
      const a = document.createElement("a");
      a.href = url;
      a.download = "plm-config.json";
      a.click();
      URL.revokeObjectURL(url);
    },
  });

  const runImport = useMutation({
    mutationFn: ({ content, dryRun }: { content: Bundle; dryRun: boolean }) =>
      unwrap(api.POST("/api/v1/configuration/import", { params: { query: { dryRun } }, body: content })),
    onSuccess: (plan) => {
      // An applied import changes reference data everywhere.
      if (plan.applied) void qc.invalidateQueries();
    },
  });

  const choose = async (file: File | undefined) => {
    if (!file) return;
    setReadError(undefined);
    runImport.reset();
    try {
      const content = JSON.parse(await file.text()) as Bundle;
      setBundle({ name: file.name, content });
      runImport.mutate({ content, dryRun: true });
    } catch {
      setBundle(undefined);
      setReadError(t("{file} is not a JSON configuration file.", { file: file.name }));
    }
  };

  const plan = runImport.data;
  const changes = plan?.changes.filter((c) => showUnchanged || c.action !== "UNCHANGED") ?? [];
  const pending = (plan?.creates ?? 0) + (plan?.updates ?? 0);

  return (
    <div className="stack">
      <div className="cols-even">
        <Panel title={t("Export")} note={t("Everything set up on this page, as one file")}>
          <p style={{ marginTop: 0 }}>
            {t(
              "Types, attributes, layouts, value lists, numbering schemes, rules, lifecycles and team roles. Items refer to each other by name, so the file applies to any environment. Commit it to Git to version your configuration.",
            )}
          </p>
          <ErrorBanner error={exportConfig.error} />
          <button className="btn primary" onClick={() => exportConfig.mutate()} disabled={exportConfig.isPending}>
            <Download size={14} aria-hidden />
            {t("Download {file}", { file: "plm-config.json" })}
          </button>
        </Panel>
        <Panel title={t("Import")} note={t("Adds and updates; never deletes")}>
          <p style={{ marginTop: 0 }}>
            {t(
              "Choose a configuration file to see what it would change here. Nothing changes until you apply, and then everything is applied together or not at all.",
            )}
          </p>
          <input
            ref={fileInput}
            type="file"
            accept="application/json,.json"
            aria-label={t("Configuration file")}
            hidden
            onChange={(e) => {
              void choose(e.target.files?.[0]);
              // Lets the same file be chosen again after it was edited.
              e.target.value = "";
            }}
          />
          <button className="btn" onClick={() => fileInput.current?.click()}>
            <FileUp size={14} aria-hidden />
            {t("Choose file…")}
          </button>
          {bundle && (
            <span className="muted" style={{ marginLeft: 8 }}>
              {bundle.name}
            </span>
          )}
          {readError && (
            <div className="banner error" role="alert" style={{ marginTop: 10 }}>
              {readError}
            </div>
          )}
        </Panel>
      </div>

      {(runImport.error || plan) && (
        <Panel
          title={plan?.applied ? t("Applied") : t("What this import would do")}
          note={
            plan &&
            [
              t("{n} to create", { n: plan.creates }),
              t("{n} to update", { n: plan.updates }),
              t("{n} unchanged", { n: plan.unchanged }),
              tn("{n} conflict", "{n} conflicts", plan.conflicts),
            ].join(" · ")
          }
          actions={
            <label className="check">
              <input type="checkbox" checked={showUnchanged} onChange={(e) => setShowUnchanged(e.target.checked)} />
              {t("Show unchanged")}
            </label>
          }
          flush
          footer={
            plan &&
            !plan.applied && (
              <>
                <button
                  className="btn primary"
                  disabled={runImport.isPending || plan.conflicts > 0 || pending === 0 || !bundle}
                  onClick={() => bundle && runImport.mutate({ content: bundle.content, dryRun: false })}
                >
                  <Upload size={14} aria-hidden />
                  {tn("Apply {n} change", "Apply {n} changes", pending)}
                </button>
                <span className="muted">
                  {plan.conflicts > 0
                    ? t("Resolve the conflicts first: nothing is applied while there are any.")
                    : pending === 0
                      ? t("This environment already matches the file.")
                      : t("Applied in one transaction, and recorded in the audit trail.")}
                </span>
              </>
            )
          }
        >
          <ErrorBanner error={runImport.error} />
          {plan && (
            <div className="grid-wrap">
              <table className="grid" aria-label={t("Import plan")}>
                <thead>
                  <tr>
                    <th>{t("Change")}</th>
                    <th>{t("What")}</th>
                    <th>{t("Name")}</th>
                    <th>{t("Details")}</th>
                  </tr>
                </thead>
                <tbody>
                  {changes.length === 0 && (
                    <tr>
                      <td colSpan={4} className="muted">
                        {t("Nothing to change.")}
                      </td>
                    </tr>
                  )}
                  {changes.map((c) => (
                    <tr key={`${c.kind} ${c.key}`}>
                      <td className="nowrap">
                        <span className={`tag change-${c.action.toLowerCase()}`}>{ACTION_LABEL()[c.action]}</span>
                      </td>
                      <td className="nowrap">{KIND_LABEL()[c.kind] ?? c.kind}</td>
                      <td className="nowrap">
                        <code>{c.key}</code>
                      </td>
                      <td className="muted">{c.details?.join(" · ")}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Panel>
      )}
    </div>
  );
}
