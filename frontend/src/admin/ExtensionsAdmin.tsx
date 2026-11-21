import { CircleAlert, CircleCheck, FileUp, Package, Power, Trash2, Upload } from "lucide-react";
import { useState, type ReactNode } from "react";
import { Link } from "react-router";
import { useAudit } from "../api/queries";
import { Dialog, ErrorBanner, Loading, Panel, PrincipalName, formatDate } from "../components/common";
import {
  useConfigureExtension,
  useEnableExtension,
  useExtensionInvocations,
  useExtensions,
  useInstallExtension,
  usePlatform,
  usePreviewExtension,
  useUninstallExtension,
  type Extension,
  type ExtensionManifest,
  type ExtensionPreview,
} from "./adminQueries";
import { operationLabel } from "./RulesAdmin";
import { t } from "../i18n/i18n";

const STATUS = (): Record<string, { label: string; className: string }> => ({
  ACTIVE: { label: t("Active"), className: "tag allow" },
  DISABLED: { label: t("Disabled"), className: "tag" },
  INCOMPATIBLE: { label: t("Incompatible"), className: "tag deny" },
});

/**
 * Extension packages (roadmap 2.3): what's installed, each one's settings, actions and history, and
 * installing or upgrading from a manifest, always previewed first.
 */
export function ExtensionsAdmin() {
  const { data: extensions, isLoading, error } = useExtensions();
  const [selected, setSelected] = useState<string | null>(null);
  const [installing, setInstalling] = useState(false);
  const current = extensions?.find((e) => e.id === selected) ?? extensions?.[0];

  if (isLoading) return <Loading />;
  return (
    <div className="admin-split">
      <Panel
        title={t("Extensions")}
        flush
        actions={
          <button className="btn small primary" onClick={() => setInstalling(true)}>
            <Upload size={13} aria-hidden />
            {t("Install or upgrade")}
          </button>
        }
      >
        <ErrorBanner error={error} />
        {!extensions?.length ? (
          <p className="empty">{t("No extensions yet. Install one from its manifest, {file}.", { file: "plm-extension.yaml" })}</p>
        ) : (
          <ul className="type-tree" aria-label={t("Installed extensions")}>
            {extensions.map((e) => (
              <li key={e.id}>
                <button className={current?.id === e.id ? "type-row active" : "type-row"} onClick={() => setSelected(e.id)}>
                  <Package size={14} className="icon-muted" aria-hidden />
                  {e.name}
                  <span className="muted" style={{ marginLeft: "auto" }}>
                    {e.version}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </Panel>
      {current ? <ExtensionDetail key={current.id} extension={current} /> : <div />}
      {installing && (
        <InstallDialog
          onClose={(id) => {
            setInstalling(false);
            if (id) setSelected(id);
          }}
        />
      )}
    </div>
  );
}

// ---------- One extension ----------

function ExtensionDetail({ extension: e }: { extension: Extension }) {
  const { data: platform } = usePlatform();
  const enable = useEnableExtension();
  const uninstall = useUninstallExtension();
  const configure = useConfigureExtension();
  const [config, setConfig] = useState<Record<string, unknown>>({});
  const statuses = STATUS();
  const status = statuses[e.status] ?? statuses.DISABLED!;
  const dirty = Object.keys(config).length > 0;

  return (
    <div style={{ display: "grid", gap: 12 }}>
      <Panel
        title={
          <span className="row" style={{ gap: 8 }}>
            {e.name} <span className="version">{e.version}</span>
            <span className={status.className}>{status.label}</span>
          </span>
        }
        actions={
          <>
            <button
              className="btn small"
              disabled={enable.isPending}
              onClick={() => enable.mutate({ id: e.id, enabled: !e.enabled })}
              title={e.enabled ? t("Its actions disappear until enabled again") : undefined}
            >
              <Power size={13} aria-hidden />
              {e.enabled ? t("Disable") : t("Enable")}
            </button>
            <button
              className="btn small danger"
              disabled={uninstall.isPending}
              onClick={() =>
                window.confirm(
                  t("Uninstall {name}? Its actions go away; configuration it added (types, attributes, value lists) stays.", {
                    name: e.name,
                  }),
                ) && uninstall.mutate(e.id)
              }
            >
              <Trash2 size={13} aria-hidden />
              {t("Uninstall")}
            </button>
          </>
        }
      >
        <ErrorBanner error={enable.error ?? uninstall.error} />
        {e.status === "INCOMPATIBLE" && (
          <div className="banner error">
            {t(
              "It supports platform {platform}, and this platform is {current}. It's switched off until you install a version that supports it.",
              {
                platform: e.platform,
                current: platform?.contractVersion ?? "",
              },
            )}
          </div>
        )}
        {e.description && <p style={{ margin: "0 0 8px" }}>{e.description}</p>}
        <dl className="props cols-2">
          <Prop label={t("Id")}>
            <code>{e.id}</code>
          </Prop>
          <Prop label={t("Publisher")}>{e.publisher ?? "—"}</Prop>
          <Prop label={t("Supports platform")}>
            <code>{e.platform}</code>
          </Prop>
          <Prop label={t("Installed")}>
            {formatDate(e.installedAt)} · <PrincipalName id={e.installedBy} />
          </Prop>
        </dl>
      </Panel>

      <Panel title={t("Permissions")} note={t("What it may do, accepted when it was installed")}>
        <Permissions list={e.permissions} />
      </Panel>

      {Object.keys(e.manifest.config ?? {}).length > 0 && (
        <Panel
          title={t("Settings")}
          actions={
            <button
              className="btn small primary"
              disabled={!dirty || configure.isPending}
              onClick={() => configure.mutate({ id: e.id, config }, { onSuccess: () => setConfig({}) })}
            >
              {t("Save settings")}
            </button>
          }
        >
          <ErrorBanner error={configure.error} />
          <ConfigForm
            idPrefix="ext-settings"
            manifest={e.manifest}
            values={{ ...e.config, ...config }}
            secretsSet={e.secretsSet}
            onChange={(k, v) => setConfig({ ...config, [k]: v })}
          />
        </Panel>
      )}

      {(e.manifest.actions?.length ?? 0) > 0 && (
        <Panel title={t("Actions it adds")} note={t("Adjust or hide them under Admin → Actions")} flush>
          <ManifestActions manifest={e.manifest} />
        </Panel>
      )}

      {(e.manifest.subscriptions?.length ?? 0) > 0 && (
        <Panel title={t("Events it's told about")} note={t("Deliveries are under Admin → Webhooks")} flush>
          <ManifestSubscriptions manifest={e.manifest} />
        </Panel>
      )}

      {(e.manifest.rules?.length ?? 0) > 0 && (
        <Panel title={t("Rules it adds")} note={t("Listed with the site's under Admin → Rules")} flush>
          <ManifestRules manifest={e.manifest} />
        </Panel>
      )}

      {e.manifest.actions?.some((a) => a.handler.webhook) && <RecentCalls extensionId={e.id} />}

      <ExtensionHistory auditId={e.auditId} />
    </div>
  );
}

function Prop({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="prop">
      <dt>{label}</dt>
      <dd>{children}</dd>
    </div>
  );
}

function Permissions({ list, added = [] }: { list: string[]; added?: string[] }) {
  const { data: platform } = usePlatform();
  if (list.length === 0)
    return (
      <p className="muted" style={{ margin: 0 }}>
        {t("None.")}
      </p>
    );
  return (
    <ul className="perm-items">
      {list.map((p) => (
        <li key={p}>
          <code>{p}</code> {platform?.extensionPermissions[p] ?? ""}
          {added.includes(p) && <span className="tag accent">{t("New")}</span>}
        </li>
      ))}
    </ul>
  );
}

function ManifestActions({ manifest, highlight }: { manifest: ExtensionManifest; highlight?: ExtensionPreview }) {
  if (!manifest.actions?.length) return <p className="empty">{t("None.")}</p>;
  return (
    <table className="grid" aria-label={t("Extension actions")}>
      <thead>
        <tr>
          <th>{t("Action")}</th>
          <th>{t("Shows in")}</th>
          <th>{t("Applies to")}</th>
          <th>{t("Does")}</th>
        </tr>
      </thead>
      <tbody>
        {manifest.actions.map((a) => (
          <tr key={a.id}>
            <td>
              <strong>{a.label}</strong>
              {highlight?.actionsAdded.includes(a.id) && (
                <span className="tag accent" style={{ marginLeft: 6 }}>
                  {t("New")}
                </span>
              )}
              {highlight?.actionsChanged.includes(a.id) && (
                <span className="tag warn" style={{ marginLeft: 6 }}>
                  {t("Changed")}
                </span>
              )}
              <div className="muted small">{a.description}</div>
            </td>
            <td className="nowrap">{a.placement === "object.toolbar" ? t("Toolbar") : t("More menu")}</td>
            <td className="muted">
              {a.appliesTo?.types?.length ? a.appliesTo.types.join(", ") : t("All types")} ·{" "}
              {a.appliesTo?.states?.length ? a.appliesTo.states.join(", ") : t("all states")}
              {a.visibleWhen ? ` · ${t("when {condition}", { condition: a.visibleWhen })}` : ""}
            </td>
            <td>
              {a.handler.webhook ? (
                <>
                  <span className="tag">{t("calls")}</span> <code className="wrap-code">{a.handler.webhook.url}</code>
                  {a.form && Object.keys(a.form.properties ?? {}).length > 0 && (
                    <div className="muted small">
                      {t("Asks: {fields}", {
                        fields: Object.values(a.form.properties ?? {})
                          .map((f) => f.title)
                          .join(", "),
                      })}
                    </div>
                  )}
                </>
              ) : (
                <code className="wrap-code">{a.handler.url}</code>
              )}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function ManifestRules({ manifest, highlight }: { manifest: ExtensionManifest; highlight?: ExtensionPreview }) {
  if (!manifest.rules?.length) return null;
  return (
    <table className="grid" aria-label={t("Extension rules")}>
      <thead>
        <tr>
          <th>{t("Rule")}</th>
          <th>{t("Refuses")}</th>
          <th>{t("Refuse when")}</th>
          <th>{t("Message")}</th>
        </tr>
      </thead>
      <tbody>
        {manifest.rules.map((r) => (
          <tr key={r.id}>
            <td>
              <strong>{r.name ?? r.id}</strong>
              {highlight?.rulesAdded.includes(r.id) && (
                <span className="tag accent" style={{ marginLeft: 6 }}>
                  {t("New")}
                </span>
              )}
              {highlight?.rulesChanged.includes(r.id) && (
                <span className="tag warn" style={{ marginLeft: 6 }}>
                  {t("Changed")}
                </span>
              )}
              <div className="muted small">{r.appliesTo?.types?.length ? r.appliesTo.types.join(", ") : t("All types")}</div>
            </td>
            <td className="nowrap">{r.on.map((o) => operationLabel(o)).join(", ")}</td>
            <td>
              <code className="wrap-code">{r.when}</code>
            </td>
            <td>{r.reject}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function ManifestSubscriptions({ manifest }: { manifest: ExtensionManifest }) {
  if (!manifest.subscriptions?.length) return null;
  return (
    <table className="grid" aria-label={t("Extension subscriptions")}>
      <thead>
        <tr>
          <th>{t("Subscription")}</th>
          <th>{t("Events")}</th>
          <th>{t("Only when")}</th>
          <th>{t("Sends to")}</th>
        </tr>
      </thead>
      <tbody>
        {manifest.subscriptions.map((sub) => (
          <tr key={sub.id}>
            <td>
              <strong>{sub.name ?? sub.id}</strong>
            </td>
            <td>
              {sub.events.map((e) => (
                <code key={e} className="tag" style={{ marginRight: 4 }}>
                  {e}
                </code>
              ))}
            </td>
            <td>{sub.filter ? <code className="wrap-code">{sub.filter}</code> : <span className="muted">{t("Always")}</span>}</td>
            <td>
              <code className="wrap-code">{sub.deliver.webhook.url}</code>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/** Settings form generated from the manifest's `config` section. Secrets are never shown, only whether they're set. */
function ConfigForm({
  idPrefix,
  manifest,
  values,
  secretsSet = [],
  onChange,
}: {
  /** Keeps field ids unique when two forms are on screen (the dialog over the details). */
  idPrefix: string;
  manifest: ExtensionManifest;
  values: Record<string, unknown>;
  secretsSet?: string[];
  onChange: (key: string, value: unknown) => void;
}) {
  return (
    <div className="attr-form cols-2">
      {Object.entries(manifest.config ?? {}).map(([key, f]) => {
        const id = `${idPrefix}-${key}`;
        const value = values[key];
        return (
          <div className="field" key={key}>
            <div className="label-row">
              <label htmlFor={id}>{f.label ?? key}</label>
              {f.required && (
                <span aria-hidden className="required">
                  *
                </span>
              )}
            </div>
            {f.type === "boolean" ? (
              <input
                id={id}
                type="checkbox"
                checked={value === true}
                onChange={(ev) => onChange(key, ev.target.checked)}
                style={{ width: "auto" }}
              />
            ) : f.type === "choice" ? (
              <select id={id} value={String(value ?? "")} onChange={(ev) => onChange(key, ev.target.value)}>
                <option value="" />
                {f.options.map((o) => (
                  <option key={o} value={o}>
                    {o}
                  </option>
                ))}
              </select>
            ) : (
              <input
                id={id}
                type={f.type === "secret" ? "password" : f.type === "number" ? "number" : f.type === "url" ? "url" : "text"}
                autoComplete="off"
                placeholder={f.type === "secret" && secretsSet.includes(key) ? t("Set: leave empty to keep it") : ""}
                value={f.type === "secret" ? String(value ?? "") : String(value ?? "")}
                onChange={(ev) => onChange(key, ev.target.value)}
              />
            )}
            {f.description && <span className="hint">{f.description}</span>}
          </div>
        );
      })}
    </div>
  );
}

const CALL_STATUS: Record<string, string> = { OK: "tag allow", FAILED: "tag warn", ERROR: "tag deny", TIMEOUT: "tag deny" };

function callStatusLabel(status: string): string {
  switch (status) {
    case "OK":
      return t("ok");
    case "FAILED":
      return t("failed");
    case "ERROR":
      return t("error");
    case "TIMEOUT":
      return t("timeout");
    default:
      return status.toLowerCase();
  }
}

/** What its actions did lately: who ran what on which object, and how it went. */
function RecentCalls({ extensionId }: { extensionId: string }) {
  const { data } = useExtensionInvocations(extensionId);
  return (
    <Panel title={t("Recent calls")} note={data ? `${data.length}${data.length === 50 ? "+" : ""}` : undefined} flush>
      {!data?.length ? (
        <p className="empty">{t("Its actions haven't called its service yet.")}</p>
      ) : (
        <table className="grid" aria-label={t("Recent calls")}>
          <thead>
            <tr>
              <th>{t("When")}</th>
              <th>{t("Action")}</th>
              <th>{t("Object")}</th>
              <th>{t("Who")}</th>
              <th>{t("Result")}</th>
              <th>{t("Took")}</th>
            </tr>
          </thead>
          <tbody>
            {data.map((c) => (
              <tr key={c.id}>
                <td className="nowrap muted">{formatDate(c.at)}</td>
                <td className="nowrap">
                  <code>{c.actionId.slice(extensionId.length + 1)}</code>
                </td>
                <td className="nowrap">
                  <Link className="num-link" to={`/objects/${c.objectId}`}>
                    {c.objectNumber}
                  </Link>
                </td>
                <td className="nowrap">
                  <PrincipalName id={c.actor} />
                </td>
                <td>
                  <span className={CALL_STATUS[c.status] ?? "tag"}>{callStatusLabel(c.status)}</span>
                  {c.httpStatus != null && <span className="muted"> {c.httpStatus}</span>} {c.message}
                </td>
                <td className="nowrap muted">{t("{ms} ms", { ms: c.durationMs })}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Panel>
  );
}

function ExtensionHistory({ auditId }: { auditId: string }) {
  const { data } = useAudit(auditId, true);
  return (
    <Panel title={t("History")} note={data ? String(data.length) : undefined} flush>
      {!data?.length ? (
        <p className="empty">{t("Nothing yet.")}</p>
      ) : (
        <table className="grid" aria-label={t("Extension history")}>
          <thead>
            <tr>
              <th>{t("When")}</th>
              <th>{t("What")}</th>
              <th>{t("Who")}</th>
            </tr>
          </thead>
          <tbody>
            {data.map((entry) => {
              const d = entry.details as { change?: string; version?: string; previousVersion?: string };
              return (
                <tr key={entry.seq}>
                  <td className="nowrap muted">{formatDate(entry.occurredAt)}</td>
                  <td>
                    {(d.change ?? "").charAt(0) + (d.change ?? "").slice(1).toLowerCase()}{" "}
                    {d.previousVersion ? `${d.previousVersion} → ` : ""}
                    {d.version}
                  </td>
                  <td className="nowrap">
                    <PrincipalName id={entry.actor} />
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
    </Panel>
  );
}

// ---------- Install or upgrade ----------

function InstallDialog({ onClose }: { onClose: (installedId?: string) => void }) {
  const preview = usePreviewExtension();
  const install = useInstallExtension();
  const { data: installed } = useExtensions();
  const [source, setSource] = useState("");
  const [config, setConfig] = useState<Record<string, unknown>>({});
  const p = preview.data;
  const existing = p ? installed?.find((e) => e.id === p.manifest.id) : undefined;
  // What the form shows: the manifest's defaults, then what's installed, then what was typed.
  const defaults = Object.fromEntries(
    Object.entries(p?.manifest.config ?? {})
      .filter(([, f]) => f.default != null)
      .map(([k, f]) => [k, f.default]),
  );
  const values = { ...defaults, ...(existing?.config ?? {}), ...config };
  const missing = Object.entries(p?.manifest.config ?? {})
    .filter(([k, f]) => f.required && (values[k] == null || values[k] === "") && !(f.type === "secret" && existing?.secretsSet.includes(k)))
    .map(([k, f]) => f.label ?? k);
  const run = (cfg = config) => preview.mutate({ manifest: source, config: cfg });

  const readFile = async (file: File | undefined) => {
    if (!file) return;
    const text = await file.text();
    setSource(text);
    setConfig({});
    preview.mutate({ manifest: text, config: {} });
  };

  return (
    <Dialog
      open
      wide
      title={
        p
          ? p.mode === "UPGRADE"
            ? t("Upgrade {name} {version}", { name: p.manifest.name, version: p.manifest.version })
            : t("Install {name} {version}", { name: p.manifest.name, version: p.manifest.version })
          : t("Install or upgrade an extension")
      }
      onClose={() => onClose()}
      footer={
        <>
          <button className="btn" onClick={() => onClose()}>
            {t("Cancel")}
          </button>
          {p ? (
            <button
              className="btn primary"
              disabled={install.isPending || preview.isPending || !p.compatible || missing.length > 0}
              title={missing.length > 0 ? t("Fill in: {fields}", { fields: missing.join(", ") }) : undefined}
              onClick={() => install.mutate({ manifest: source, config: { ...defaults, ...config } }, { onSuccess: (e) => onClose(e.id) })}
            >
              {p.mode === "UPGRADE" ? t("Upgrade from {version}", { version: p.installedVersion ?? "" }) : t("Install")}
            </button>
          ) : (
            <button className="btn primary" disabled={!source.trim() || preview.isPending} onClick={() => run()}>
              {t("Preview")}
            </button>
          )}
        </>
      }
    >
      <div style={{ display: "grid", gap: 14 }}>
        <ErrorBanner error={preview.error ?? install.error} />
        {!p ? (
          <>
            <label className="btn" style={{ justifySelf: "start" }}>
              <FileUp size={14} aria-hidden />
              {t("Choose {file}", { file: "plm-extension.yaml" })}
              <input
                type="file"
                accept=".yaml,.yml,.json"
                aria-label={t("Manifest file")}
                style={{ display: "none" }}
                onChange={(ev) => {
                  void readFile(ev.target.files?.[0]);
                  ev.target.value = "";
                }}
              />
            </label>
            <div className="field">
              <label htmlFor="ext-source">{t("Or paste the manifest")}</label>
              <textarea
                id="ext-source"
                className="code"
                rows={12}
                value={source}
                onChange={(ev) => setSource(ev.target.value)}
                spellCheck={false}
              />
            </div>
          </>
        ) : (
          <>
            <div className={p.compatible ? "banner ok" : "banner error"} role="status">
              {p.compatible ? <CircleCheck size={14} aria-hidden /> : <CircleAlert size={14} aria-hidden />}{" "}
              {p.compatible
                ? t("Compatible: it supports platform {platform}, and this platform is {current}.", {
                    platform: p.manifest.platform,
                    current: p.platformVersion,
                  })
                : t("Not compatible: it supports platform {platform}, and this platform is {current}.", {
                    platform: p.manifest.platform,
                    current: p.platformVersion,
                  })}
            </div>
            <div>
              <strong>{p.manifest.name}</strong> <code>{p.manifest.id}</code> {p.manifest.publisher ? `· ${p.manifest.publisher}` : ""}
              {p.manifest.description && <p style={{ margin: "4px 0 0" }}>{p.manifest.description}</p>}
            </div>
            <section>
              <div className="section-label">{t("It asks to")}</div>
              <Permissions list={p.manifest.permissions} added={p.mode === "UPGRADE" ? p.permissionsAdded : []} />
              {p.permissionsRemoved.length > 0 && (
                <p className="muted small">{t("No longer asks for: {permissions}", { permissions: p.permissionsRemoved.join(", ") })}</p>
              )}
            </section>
            {((p.manifest.actions?.length ?? 0) > 0 || p.actionsRemoved.length > 0) && (
              <section>
                <div className="section-label">{t("Actions")}</div>
                <ManifestActions manifest={p.manifest} highlight={p.mode === "UPGRADE" ? p : undefined} />
                {p.actionsRemoved.length > 0 && (
                  <p className="muted small">{t("Removed: {items}", { items: p.actionsRemoved.join(", ") })}</p>
                )}
              </section>
            )}
            {(p.manifest.subscriptions?.length ?? 0) > 0 && (
              <section>
                <div className="section-label">{t("Events it's told about")}</div>
                <ManifestSubscriptions manifest={p.manifest} />
              </section>
            )}
            {(p.manifest.rules?.length ?? 0) > 0 && (
              <section>
                <div className="section-label">{t("Rules: it refuses operations when")}</div>
                <ManifestRules manifest={p.manifest} highlight={p.mode === "UPGRADE" ? p : undefined} />
                {p.rulesRemoved.length > 0 && <p className="muted small">{t("Removed: {items}", { items: p.rulesRemoved.join(", ") })}</p>}
              </section>
            )}
            {p.seedChanges.length > 0 && (
              <section>
                <div className="section-label">{t("Configuration it adds or changes")}</div>
                <ul className="perm-items" aria-label={t("Seed changes")}>
                  {p.seedChanges.map((c) => (
                    <li key={`${c.kind}-${c.key}`}>
                      <span className={`tag change-${c.action.toLowerCase()}`}>{c.action.toLowerCase()}</span> {c.kind} <code>{c.key}</code>
                      {c.details.length > 0 && <span className="muted"> · {c.details.join(", ")}</span>}
                    </li>
                  ))}
                </ul>
              </section>
            )}
            {Object.keys(p.manifest.config ?? {}).length > 0 && (
              <section>
                <div className="section-label">{t("Settings")}</div>
                <ConfigForm
                  idPrefix="ext-install"
                  manifest={p.manifest}
                  values={values}
                  secretsSet={existing?.secretsSet}
                  onChange={(k, v) => setConfig({ ...config, [k]: v })}
                />
                {missing.length > 0 && (
                  <div className="banner warn" style={{ marginTop: 8 }}>
                    {t("Required: {fields}", { fields: missing.join(", ") })}
                  </div>
                )}
              </section>
            )}
          </>
        )}
      </div>
    </Dialog>
  );
}
