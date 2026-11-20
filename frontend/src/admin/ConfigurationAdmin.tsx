import { Download, FileUp, Upload } from "lucide-react";
import { useRef, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api, unwrap, type Schemas } from "../api/client";
import { ErrorBanner, Panel } from "../components/common";

type Bundle = Schemas["ConfigBundle"];
type Plan = Schemas["ImportPlanResponse"];
type Action = Plan["changes"][number]["action"];

const ACTION_LABEL: Record<Action, string> = {
  CREATE: "Create",
  UPDATE: "Update",
  UNCHANGED: "Unchanged",
  CONFLICT: "Conflict",
};

const KIND_LABEL: Record<string, string> = {
  teamRole: "Team role",
  enumeration: "Value list",
  numberingScheme: "Numbering scheme",
  lifecycle: "Lifecycle",
  type: "Type",
  attribute: "Attribute",
  typeRules: "Type rules",
  typeLifecycle: "Type lifecycle",
  layout: "Layout",
};

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
      setReadError(`${file.name} is not a JSON configuration file.`);
    }
  };

  const plan = runImport.data;
  const changes = plan?.changes.filter((c) => showUnchanged || c.action !== "UNCHANGED") ?? [];
  const pending = (plan?.creates ?? 0) + (plan?.updates ?? 0);

  return (
    <div className="stack">
      <div className="cols-even">
        <Panel title="Export" note="Everything set up on this page, as one file">
          <p style={{ marginTop: 0 }}>
            Types, attributes, layouts, value lists, numbering schemes, rules, lifecycles and team roles. Items refer to each
            other by name, so the file applies to any environment. Commit it to Git to version your configuration.
          </p>
          <ErrorBanner error={exportConfig.error} />
          <button className="btn primary" onClick={() => exportConfig.mutate()} disabled={exportConfig.isPending}>
            <Download size={14} aria-hidden />
            Download plm-config.json
          </button>
        </Panel>
        <Panel title="Import" note="Adds and updates; never deletes">
          <p style={{ marginTop: 0 }}>
            Choose a configuration file to see what it would change here. Nothing changes until you apply, and then everything
            is applied together or not at all.
          </p>
          <input
            ref={fileInput}
            type="file"
            accept="application/json,.json"
            aria-label="Configuration file"
            hidden
            onChange={(e) => {
              void choose(e.target.files?.[0]);
              // Lets the same file be chosen again after it was edited.
              e.target.value = "";
            }}
          />
          <button className="btn" onClick={() => fileInput.current?.click()}>
            <FileUp size={14} aria-hidden />
            Choose file…
          </button>
          {bundle && <span className="muted" style={{ marginLeft: 8 }}>{bundle.name}</span>}
          {readError && (
            <div className="banner error" role="alert" style={{ marginTop: 10 }}>
              {readError}
            </div>
          )}
        </Panel>
      </div>

      {(runImport.error || plan) && (
        <Panel
          title={plan?.applied ? "Applied" : "What this import would do"}
          note={
            plan &&
            `${plan.creates} to create · ${plan.updates} to update · ${plan.unchanged} unchanged · ${plan.conflicts} conflict${plan.conflicts === 1 ? "" : "s"}`
          }
          actions={
            <label className="check">
              <input type="checkbox" checked={showUnchanged} onChange={(e) => setShowUnchanged(e.target.checked)} />
              Show unchanged
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
                  Apply {pending} change{pending === 1 ? "" : "s"}
                </button>
                <span className="muted">
                  {plan.conflicts > 0
                    ? "Resolve the conflicts first: nothing is applied while there are any."
                    : pending === 0
                      ? "This environment already matches the file."
                      : "Applied in one transaction, and recorded in the audit trail."}
                </span>
              </>
            )
          }
        >
          <ErrorBanner error={runImport.error} />
          {plan && (
            <div className="grid-wrap">
              <table className="grid" aria-label="Import plan">
                <thead>
                  <tr>
                    <th>Change</th>
                    <th>What</th>
                    <th>Name</th>
                    <th>Details</th>
                  </tr>
                </thead>
                <tbody>
                  {changes.length === 0 && (
                    <tr>
                      <td colSpan={4} className="muted">
                        Nothing to change.
                      </td>
                    </tr>
                  )}
                  {changes.map((c) => (
                    <tr key={`${c.kind} ${c.key}`}>
                      <td className="nowrap">
                        <span className={`tag change-${c.action.toLowerCase()}`}>{ACTION_LABEL[c.action]}</span>
                      </td>
                      <td className="nowrap">{KIND_LABEL[c.kind] ?? c.kind}</td>
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
