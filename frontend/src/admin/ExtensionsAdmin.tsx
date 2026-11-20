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
import { OPERATION_LABEL } from "./RulesAdmin";

const STATUS: Record<string, { label: string; className: string }> = {
  ACTIVE: { label: "Active", className: "tag allow" },
  DISABLED: { label: "Disabled", className: "tag" },
  INCOMPATIBLE: { label: "Incompatible", className: "tag deny" },
};

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
        title="Extensions"
        flush
        actions={
          <button className="btn small primary" onClick={() => setInstalling(true)}>
            <Upload size={13} aria-hidden />
            Install or upgrade
          </button>
        }
      >
        <ErrorBanner error={error} />
        {!extensions?.length ? (
          <p className="empty">No extensions yet. Install one from its manifest, plm-extension.yaml.</p>
        ) : (
          <ul className="type-tree" aria-label="Installed extensions">
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
  const status = STATUS[e.status] ?? STATUS.DISABLED!;
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
              title={e.enabled ? "Its actions disappear until enabled again" : undefined}
            >
              <Power size={13} aria-hidden />
              {e.enabled ? "Disable" : "Enable"}
            </button>
            <button
              className="btn small danger"
              disabled={uninstall.isPending}
              onClick={() =>
                window.confirm(
                  `Uninstall ${e.name}? Its actions go away; configuration it added (types, attributes, value lists) stays.`,
                ) && uninstall.mutate(e.id)
              }
            >
              <Trash2 size={13} aria-hidden />
              Uninstall
            </button>
          </>
        }
      >
        <ErrorBanner error={enable.error ?? uninstall.error} />
        {e.status === "INCOMPATIBLE" && (
          <div className="banner error">
            It supports platform {e.platform}, and this platform is {platform?.contractVersion}. It's switched off until you install a
            version that supports it.
          </div>
        )}
        {e.description && <p style={{ margin: "0 0 8px" }}>{e.description}</p>}
        <dl className="props cols-2">
          <Prop label="Id">
            <code>{e.id}</code>
          </Prop>
          <Prop label="Publisher">{e.publisher ?? "—"}</Prop>
          <Prop label="Supports platform">
            <code>{e.platform}</code>
          </Prop>
          <Prop label="Installed">
            {formatDate(e.installedAt)} · <PrincipalName id={e.installedBy} />
          </Prop>
        </dl>
      </Panel>

      <Panel title="Permissions" note="What it may do, accepted when it was installed">
        <Permissions list={e.permissions} />
      </Panel>

      {Object.keys(e.manifest.config ?? {}).length > 0 && (
        <Panel
          title="Settings"
          actions={
            <button
              className="btn small primary"
              disabled={!dirty || configure.isPending}
              onClick={() => configure.mutate({ id: e.id, config }, { onSuccess: () => setConfig({}) })}
            >
              Save settings
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
        <Panel title="Actions it adds" note="Adjust or hide them under Admin → Actions" flush>
          <ManifestActions manifest={e.manifest} />
        </Panel>
      )}

      {(e.manifest.subscriptions?.length ?? 0) > 0 && (
        <Panel title="Events it's told about" note="Deliveries are under Admin → Webhooks" flush>
          <ManifestSubscriptions manifest={e.manifest} />
        </Panel>
      )}

      {(e.manifest.rules?.length ?? 0) > 0 && (
        <Panel title="Rules it adds" note="Listed with the site's under Admin → Rules" flush>
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
        None.
      </p>
    );
  return (
    <ul className="perm-items">
      {list.map((p) => (
        <li key={p}>
          <code>{p}</code> {platform?.extensionPermissions[p] ?? ""}
          {added.includes(p) && <span className="tag accent">New</span>}
        </li>
      ))}
    </ul>
  );
}

function ManifestActions({ manifest, highlight }: { manifest: ExtensionManifest; highlight?: ExtensionPreview }) {
  if (!manifest.actions?.length) return <p className="empty">None.</p>;
  return (
    <table className="grid" aria-label="Extension actions">
      <thead>
        <tr>
          <th>Action</th>
          <th>Shows in</th>
          <th>Applies to</th>
          <th>Does</th>
        </tr>
      </thead>
      <tbody>
        {manifest.actions.map((a) => (
          <tr key={a.id}>
            <td>
              <strong>{a.label}</strong>
              {highlight?.actionsAdded.includes(a.id) && (
                <span className="tag accent" style={{ marginLeft: 6 }}>
                  New
                </span>
              )}
              {highlight?.actionsChanged.includes(a.id) && (
                <span className="tag warn" style={{ marginLeft: 6 }}>
                  Changed
                </span>
              )}
              <div className="muted small">{a.description}</div>
            </td>
            <td className="nowrap">{a.placement === "object.toolbar" ? "Toolbar" : "More menu"}</td>
            <td className="muted">
              {a.appliesTo?.types?.length ? a.appliesTo.types.join(", ") : "All types"} ·{" "}
              {a.appliesTo?.states?.length ? a.appliesTo.states.join(", ") : "all states"}
              {a.visibleWhen ? ` · when ${a.visibleWhen}` : ""}
            </td>
            <td>
              {a.handler.webhook ? (
                <>
                  <span className="tag">calls</span> <code className="wrap-code">{a.handler.webhook.url}</code>
                  {a.form && Object.keys(a.form.properties ?? {}).length > 0 && (
                    <div className="muted small">
                      Asks:{" "}
                      {Object.values(a.form.properties ?? {})
                        .map((f) => f.title)
                        .join(", ")}
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
    <table className="grid" aria-label="Extension rules">
      <thead>
        <tr>
          <th>Rule</th>
          <th>Refuses</th>
          <th>Refuse when</th>
          <th>Message</th>
        </tr>
      </thead>
      <tbody>
        {manifest.rules.map((r) => (
          <tr key={r.id}>
            <td>
              <strong>{r.name ?? r.id}</strong>
              {highlight?.rulesAdded.includes(r.id) && (
                <span className="tag accent" style={{ marginLeft: 6 }}>
                  New
                </span>
              )}
              {highlight?.rulesChanged.includes(r.id) && (
                <span className="tag warn" style={{ marginLeft: 6 }}>
                  Changed
                </span>
              )}
              <div className="muted small">{r.appliesTo?.types?.length ? r.appliesTo.types.join(", ") : "All types"}</div>
            </td>
            <td className="nowrap">{r.on.map((o) => OPERATION_LABEL[o] ?? o).join(", ")}</td>
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
    <table className="grid" aria-label="Extension subscriptions">
      <thead>
        <tr>
          <th>Subscription</th>
          <th>Events</th>
          <th>Only when</th>
          <th>Sends to</th>
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
            <td>{sub.filter ? <code className="wrap-code">{sub.filter}</code> : <span className="muted">Always</span>}</td>
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
                placeholder={f.type === "secret" && secretsSet.includes(key) ? "Set: leave empty to keep it" : ""}
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

/** What its actions did lately: who ran what on which object, and how it went. */
function RecentCalls({ extensionId }: { extensionId: string }) {
  const { data } = useExtensionInvocations(extensionId);
  return (
    <Panel title="Recent calls" note={data ? `${data.length}${data.length === 50 ? "+" : ""}` : undefined} flush>
      {!data?.length ? (
        <p className="empty">Its actions haven't called its service yet.</p>
      ) : (
        <table className="grid" aria-label="Recent calls">
          <thead>
            <tr>
              <th>When</th>
              <th>Action</th>
              <th>Object</th>
              <th>Who</th>
              <th>Result</th>
              <th>Took</th>
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
                  <span className={CALL_STATUS[c.status] ?? "tag"}>{c.status.toLowerCase()}</span>
                  {c.httpStatus != null && <span className="muted"> {c.httpStatus}</span>} {c.message}
                </td>
                <td className="nowrap muted">{c.durationMs} ms</td>
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
    <Panel title="History" note={data ? String(data.length) : undefined} flush>
      {!data?.length ? (
        <p className="empty">Nothing yet.</p>
      ) : (
        <table className="grid" aria-label="Extension history">
          <thead>
            <tr>
              <th>When</th>
              <th>What</th>
              <th>Who</th>
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
        p ? `${p.mode === "UPGRADE" ? "Upgrade" : "Install"} ${p.manifest.name} ${p.manifest.version}` : "Install or upgrade an extension"
      }
      onClose={() => onClose()}
      footer={
        <>
          <button className="btn" onClick={() => onClose()}>
            Cancel
          </button>
          {p ? (
            <button
              className="btn primary"
              disabled={install.isPending || preview.isPending || !p.compatible || missing.length > 0}
              title={missing.length > 0 ? `Fill in: ${missing.join(", ")}` : undefined}
              onClick={() => install.mutate({ manifest: source, config: { ...defaults, ...config } }, { onSuccess: (e) => onClose(e.id) })}
            >
              {p.mode === "UPGRADE" ? `Upgrade from ${p.installedVersion}` : "Install"}
            </button>
          ) : (
            <button className="btn primary" disabled={!source.trim() || preview.isPending} onClick={() => run()}>
              Preview
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
              Choose plm-extension.yaml
              <input
                type="file"
                accept=".yaml,.yml,.json"
                aria-label="Manifest file"
                style={{ display: "none" }}
                onChange={(ev) => {
                  void readFile(ev.target.files?.[0]);
                  ev.target.value = "";
                }}
              />
            </label>
            <div className="field">
              <label htmlFor="ext-source">Or paste the manifest</label>
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
                ? `Compatible: it supports platform ${p.manifest.platform}, and this platform is ${p.platformVersion}.`
                : `Not compatible: it supports platform ${p.manifest.platform}, and this platform is ${p.platformVersion}.`}
            </div>
            <div>
              <strong>{p.manifest.name}</strong> <code>{p.manifest.id}</code> {p.manifest.publisher ? `· ${p.manifest.publisher}` : ""}
              {p.manifest.description && <p style={{ margin: "4px 0 0" }}>{p.manifest.description}</p>}
            </div>
            <section>
              <div className="section-label">It asks to</div>
              <Permissions list={p.manifest.permissions} added={p.mode === "UPGRADE" ? p.permissionsAdded : []} />
              {p.permissionsRemoved.length > 0 && <p className="muted small">No longer asks for: {p.permissionsRemoved.join(", ")}</p>}
            </section>
            {((p.manifest.actions?.length ?? 0) > 0 || p.actionsRemoved.length > 0) && (
              <section>
                <div className="section-label">Actions</div>
                <ManifestActions manifest={p.manifest} highlight={p.mode === "UPGRADE" ? p : undefined} />
                {p.actionsRemoved.length > 0 && <p className="muted small">Removed: {p.actionsRemoved.join(", ")}</p>}
              </section>
            )}
            {(p.manifest.subscriptions?.length ?? 0) > 0 && (
              <section>
                <div className="section-label">Events it's told about</div>
                <ManifestSubscriptions manifest={p.manifest} />
              </section>
            )}
            {(p.manifest.rules?.length ?? 0) > 0 && (
              <section>
                <div className="section-label">Rules: it refuses operations when</div>
                <ManifestRules manifest={p.manifest} highlight={p.mode === "UPGRADE" ? p : undefined} />
                {p.rulesRemoved.length > 0 && <p className="muted small">Removed: {p.rulesRemoved.join(", ")}</p>}
              </section>
            )}
            {p.seedChanges.length > 0 && (
              <section>
                <div className="section-label">Configuration it adds or changes</div>
                <ul className="perm-items" aria-label="Seed changes">
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
                <div className="section-label">Settings</div>
                <ConfigForm
                  idPrefix="ext-install"
                  manifest={p.manifest}
                  values={values}
                  secretsSet={existing?.secretsSet}
                  onChange={(k, v) => setConfig({ ...config, [k]: v })}
                />
                {missing.length > 0 && (
                  <div className="banner warn" style={{ marginTop: 8 }}>
                    Required: {missing.join(", ")}
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
