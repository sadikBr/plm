import { Bot, Copy, KeyRound, Pencil, Plus, ShieldCheck, Trash2 } from "lucide-react";
import { useState } from "react";
import { Dialog, ErrorBanner, Loading, Panel, PrincipalName, formatDate } from "../components/common";
import {
  useApiTokens,
  useCreateServiceAccount,
  useIssueToken,
  useRevokeToken,
  useServiceAccounts,
  useUpdateServiceAccount,
  type ApiToken,
  type IssuedToken,
  type ServiceAccount,
} from "./adminQueries";
import { t } from "../i18n/i18n";

const KIND_LABEL = (): Record<string, string> => ({
  API_TOKEN: t("API token"),
  CLIENT_SECRET: t("Client secret"),
  ACCESS: t("Access token"),
  DELEGATED: t("Delegated"),
});

const SCOPES = (): [string, string][] => [
  ["read", t("Read")],
  ["write", t("Make changes")],
  ["admin", t("Administer")],
];

/** Where the browser reached the API: the base for the examples shown with new secrets. */
const apiBase = () => window.location.origin;

/**
 * Service accounts (roadmap 2.7): principals for integrations, with API tokens or OAuth client
 * secrets. A token acts within its scopes and its account's access rules; secrets show once.
 */
export function ServiceAccountsAdmin() {
  const { data: accounts, isLoading, error } = useServiceAccounts();
  const [editing, setEditing] = useState<ServiceAccount | "new" | null>(null);
  const [selected, setSelected] = useState("");
  const current = accounts?.find((a) => a.id === selected) ?? accounts?.[0];

  if (isLoading) return <Loading />;
  return (
    <div style={{ display: "grid", gap: 12 }}>
      <Panel
        title={t("Service accounts")}
        note={t("For integrations: they sign in with a token, and get access through teams and grants like people")}
        flush
        actions={
          <button className="btn small primary" onClick={() => setEditing("new")}>
            <Plus size={13} aria-hidden />
            {t("New service account")}
          </button>
        }
      >
        <ErrorBanner error={error} />
        {!accounts?.length ? (
          <p className="empty">
            {t("No service accounts yet. Create one for each system that calls the API, so each can be stopped on its own.")}
          </p>
        ) : (
          <table className="grid" aria-label={t("Service accounts")}>
            <thead>
              <tr>
                <th>{t("Account")}</th>
                <th>{t("Username")}</th>
                <th>{t("Can administer")}</th>
                <th>{t("From")}</th>
                <th>{t("Created")}</th>
                <th aria-label={t("Actions")} />
              </tr>
            </thead>
            <tbody>
              {accounts.map((a) => (
                <tr
                  key={a.id}
                  className={[a.active ? "" : "inactive", current?.id === a.id ? "selected" : ""].join(" ").trim() || undefined}
                >
                  <td>
                    <button className="link-btn" onClick={() => setSelected(a.id)} title={t("Show its tokens")}>
                      <Bot size={13} aria-hidden /> <strong>{a.displayName}</strong>
                    </button>
                    {a.description && <div className="muted small">{a.description}</div>}
                    {!a.active && <div className="muted small">{t("Inactive: its tokens don't work")}</div>}
                  </td>
                  <td>
                    <code>{a.username}</code>
                  </td>
                  <td>
                    {a.siteAdmin ? (
                      <span className="tag warn">{t("Yes, with admin tokens")}</span>
                    ) : (
                      <span className="muted">{t("No")}</span>
                    )}
                  </td>
                  <td className="nowrap">{a.extensionId ? <code>{a.extensionId}</code> : t("This site")}</td>
                  <td className="nowrap muted">{formatDate(a.createdAt)}</td>
                  <td className="nowrap">
                    <button className="icon-btn" aria-label={t("Edit {name}", { name: a.displayName })} onClick={() => setEditing(a)}>
                      <Pencil size={13} />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Panel>
      {current && <Tokens account={current} />}
      {editing && <AccountDialog account={editing === "new" ? null : editing} onClose={() => setEditing(null)} />}
    </div>
  );
}

/** The tag class, whether the token works, and its status as shown. */
function tokenStatus(token: ApiToken): [string, boolean, string] {
  if (token.revokedAt) return ["tag deny", false, t("revoked")];
  if (token.expiresAt && new Date(token.expiresAt) < new Date()) return ["tag", false, t("expired")];
  return ["tag allow", true, t("active")];
}

/** The account's tokens and client secrets; never the secrets themselves, only how they start. */
function Tokens({ account }: { account: ServiceAccount }) {
  const { data: tokens } = useApiTokens(account.id);
  const revoke = useRevokeToken();
  const [issuing, setIssuing] = useState(false);
  const [issued, setIssued] = useState<IssuedToken | null>(null);
  const shown = (tokens ?? []).filter((tok) => tok.kind === "API_TOKEN" || tok.kind === "CLIENT_SECRET");

  return (
    <Panel
      title={t("Tokens of {name}", { name: account.displayName })}
      note={t("Client secrets are exchanged for one-hour access tokens (OAuth client credentials)")}
      flush
      actions={
        <button className="btn small" disabled={!account.active} onClick={() => setIssuing(true)}>
          <KeyRound size={13} aria-hidden />
          {t("Issue token")}
        </button>
      }
    >
      <ErrorBanner error={revoke.error} />
      {issued && <NewSecret issued={issued} account={account} onDone={() => setIssued(null)} />}
      {!shown.length ? (
        <p className="empty">{t("No tokens yet.")}</p>
      ) : (
        <table className="grid" aria-label={t("Tokens")}>
          <thead>
            <tr>
              <th>{t("Name")}</th>
              <th>{t("Kind")}</th>
              <th>{t("Starts with")}</th>
              <th>{t("Scopes")}</th>
              <th>{t("Expires")}</th>
              <th>{t("Last used")}</th>
              <th>{t("Status")}</th>
              <th aria-label={t("Actions")} />
            </tr>
          </thead>
          <tbody>
            {shown.map((tok) => {
              const [cls, works, label] = tokenStatus(tok);
              return (
                <tr key={tok.id} className={works ? undefined : "inactive"}>
                  <td>
                    <strong>{tok.name}</strong>
                    <div className="muted small">
                      {t("by")} <PrincipalName id={tok.createdBy} />
                    </div>
                  </td>
                  <td className="nowrap">{KIND_LABEL()[tok.kind] ?? tok.kind}</td>
                  <td>
                    <code>{tok.prefix}…</code>
                  </td>
                  <td>
                    <div className="row wrap" style={{ gap: 4 }}>
                      {tok.scopes.map((s) => (
                        <span key={s} className={s === "admin" ? "tag warn" : "tag"}>
                          {s}
                        </span>
                      ))}
                    </div>
                  </td>
                  <td className="nowrap muted">{tok.expiresAt ? formatDate(tok.expiresAt) : t("Never")}</td>
                  <td className="nowrap muted">{tok.lastUsedAt ? formatDate(tok.lastUsedAt) : t("Not yet")}</td>
                  <td>
                    <span className={cls}>{label}</span>
                  </td>
                  <td className="nowrap">
                    {!tok.revokedAt && (
                      <button
                        className="icon-btn"
                        aria-label={t("Revoke {name}", { name: tok.name })}
                        title={t("Revoke: it stops working at once")}
                        disabled={revoke.isPending}
                        onClick={() =>
                          window.confirm(t("Revoke “{name}”? Whatever uses it stops working at once.", { name: tok.name })) &&
                          revoke.mutate(tok.id)
                        }
                      >
                        <Trash2 size={13} />
                      </button>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
      {issuing && (
        <IssueDialog
          account={account}
          onClose={() => setIssuing(false)}
          onIssued={(tok) => {
            setIssuing(false);
            setIssued(tok);
          }}
        />
      )}
    </Panel>
  );
}

/** The secret, shown this once, with how to use it. */
function NewSecret({ issued, account, onDone }: { issued: IssuedToken; account: ServiceAccount; onDone: () => void }) {
  const [copied, setCopied] = useState(false);
  const client = issued.token.kind === "CLIENT_SECRET";
  const example = client
    ? `curl -X POST ${apiBase()}/api/v1/oauth/token \\\n  -d grant_type=client_credentials \\\n  -d client_id=${account.id} \\\n  -d client_secret=${issued.secret}`
    : `curl ${apiBase()}/api/v1/me \\\n  -H "Authorization: Bearer ${issued.secret}"`;
  return (
    <div className="banner ok secret-box" role="status">
      <ShieldCheck size={16} aria-hidden />
      <div className="grow" style={{ display: "grid", gap: 6, minWidth: 0 }}>
        <strong>{t("Copy “{name}” now: it won't be shown again.", { name: issued.token.name })}</strong>
        <div className="row" style={{ gap: 6 }}>
          <code className="secret" aria-label={t("New secret")}>
            {issued.secret}
          </code>
          <button
            className="btn small"
            onClick={() =>
              void navigator.clipboard?.writeText(issued.secret).then(
                () => setCopied(true),
                () => setCopied(false),
              )
            }
          >
            <Copy size={12} aria-hidden /> {copied ? t("Copied") : t("Copy")}
          </button>
        </div>
        <span className="small">{client ? t("Exchange it for an access token:") : t("Use it as a bearer token:")}</span>
        <pre className="wrap-code example">{example}</pre>
      </div>
      <button className="btn small" onClick={onDone}>
        {t("Done")}
      </button>
    </div>
  );
}

function IssueDialog({
  account,
  onClose,
  onIssued,
}: {
  account: ServiceAccount;
  onClose: () => void;
  onIssued: (token: IssuedToken) => void;
}) {
  const issue = useIssueToken();
  const [name, setName] = useState("");
  const [kind, setKind] = useState<"API_TOKEN" | "CLIENT_SECRET">("API_TOKEN");
  const [scopes, setScopes] = useState<string[]>(["read"]);
  const [days, setDays] = useState("90");
  const toggle = (s: string) => setScopes(scopes.includes(s) ? scopes.filter((x) => x !== s) : [...scopes, s]);

  return (
    <Dialog
      open
      title={t("Issue a token for {name}", { name: account.displayName })}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            {t("Cancel")}
          </button>
          <button
            className="btn primary"
            disabled={issue.isPending || !name.trim() || !scopes.length}
            onClick={() =>
              issue.mutate(
                { id: account.id, body: { name, kind, scopes, expiresInDays: days ? Number(days) : null } },
                { onSuccess: onIssued },
              )
            }
          >
            {t("Issue")}
          </button>
        </>
      }
    >
      <div className="attr-form">
        <ErrorBanner error={issue.error} />
        <div className="field wide">
          <label htmlFor="tok-name">{t("Name")}</label>
          <input id="tok-name" value={name} onChange={(e) => setName(e.target.value)} placeholder={t("Nightly ERP sync")} autoFocus />
          <span className="hint">{t("What uses it, to recognize it when it's time to revoke.")}</span>
        </div>
        <fieldset className="check-list wide">
          <legend>{t("Kind")}</legend>
          <label className="check">
            <input type="radio" name="tok-kind" checked={kind === "API_TOKEN"} onChange={() => setKind("API_TOKEN")} />
            {t("API token: sent as a bearer token as is")}
          </label>
          <label className="check">
            <input type="radio" name="tok-kind" checked={kind === "CLIENT_SECRET"} onChange={() => setKind("CLIENT_SECRET")} />
            {t("Client secret: exchanged for one-hour access tokens (OAuth client credentials)")}
          </label>
        </fieldset>
        <fieldset className="check-list wide">
          <legend>{t("Scopes")}</legend>
          {SCOPES()
            .filter(([s]) => s !== "admin" || account.siteAdmin)
            .map(([s, label]) => (
              <label key={s} className="check">
                <input type="checkbox" checked={scopes.includes(s)} onChange={() => toggle(s)} />
                {label} <code>{s}</code>
              </label>
            ))}
          <span className="hint">{t("On top of the account's own access: a token can't do what its account can't.")}</span>
        </fieldset>
        <div className="field">
          <label htmlFor="tok-days">{t("Expires")}</label>
          <select id="tok-days" value={days} onChange={(e) => setDays(e.target.value)}>
            <option value="30">{t("In {n} days", { n: 30 })}</option>
            <option value="90">{t("In {n} days", { n: 90 })}</option>
            <option value="365">{t("In a year")}</option>
            <option value="">{t("Never")}</option>
          </select>
        </div>
      </div>
    </Dialog>
  );
}

function AccountDialog({ account, onClose }: { account: ServiceAccount | null; onClose: () => void }) {
  const create = useCreateServiceAccount();
  const update = useUpdateServiceAccount();
  const [name, setName] = useState("");
  const [displayName, setDisplayName] = useState(account?.displayName ?? "");
  const [description, setDescription] = useState(account?.description ?? "");
  const [siteAdmin, setSiteAdmin] = useState(account?.siteAdmin ?? false);
  const [active, setActive] = useState(account?.active ?? true);
  const saving = create.isPending || update.isPending;

  const save = () => {
    if (account)
      update.mutate({ id: account.id, body: { displayName, description: description || null, siteAdmin, active } }, { onSuccess: onClose });
    else create.mutate({ name, displayName, description: description || null, siteAdmin }, { onSuccess: onClose });
  };

  return (
    <Dialog
      open
      title={account ? t("Edit {name}", { name: account.displayName }) : t("New service account")}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            {t("Cancel")}
          </button>
          <button className="btn primary" disabled={saving || !displayName.trim() || (!account && !name.trim())} onClick={save}>
            {t("Save")}
          </button>
        </>
      }
    >
      <div className="attr-form">
        <ErrorBanner error={create.error ?? update.error} />
        {!account && (
          <div className="field wide">
            <label htmlFor="sa-name">{t("Name")}</label>
            <input id="sa-name" value={name} onChange={(e) => setName(e.target.value.toLowerCase())} placeholder="erp-sync" autoFocus />
            <span className="hint">
              {t("Lower-case letters, digits and dashes. Its username is")} <code>svc-{name || t("name")}</code>.
            </span>
          </div>
        )}
        <div className="field wide">
          <label htmlFor="sa-display">{t("Display name")}</label>
          <input id="sa-display" value={displayName} onChange={(e) => setDisplayName(e.target.value)} placeholder={t("ERP sync")} />
        </div>
        <div className="field wide">
          <label htmlFor="sa-desc">{t("Description")}</label>
          <input
            id="sa-desc"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder={t("What it does, who owns it")}
          />
        </div>
        <label className="check wide">
          <input type="checkbox" checked={siteAdmin} onChange={(e) => setSiteAdmin(e.target.checked)} />
          {t("Can administer the site (with tokens that have the admin scope)")}
        </label>
        {account && (
          <label className="check wide">
            <input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} />
            {t("Active: unchecked, none of its tokens work")}
          </label>
        )}
      </div>
    </Dialog>
  );
}
