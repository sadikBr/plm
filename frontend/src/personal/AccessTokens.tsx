import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Copy, KeyRound, Trash2 } from "lucide-react";
import { useState } from "react";
import { api, unwrap } from "../api/client";
import { config } from "../config";
import { EmptyState, ErrorBanner, Panel, When } from "../components/common";
import { t } from "../i18n/i18n";

/** A person's own access tokens (roadmap 8.3), for the desktop sync client and scripts. */
export function AccessTokensPanel() {
  const qc = useQueryClient();
  const { data: tokens } = useQuery({ queryKey: ["me", "tokens"], queryFn: () => unwrap(api.GET("/api/v1/me/tokens")) });
  const [name, setName] = useState("");
  const [write, setWrite] = useState(true);
  const [days, setDays] = useState("90");
  const [secret, setSecret] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const issue = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/api/v1/me/tokens", {
          body: { name, scopes: write ? ["read", "write"] : ["read"], expiresInDays: days ? Number(days) : null },
        }),
      ),
    onSuccess: (r) => {
      setSecret(r.secret);
      setCopied(false);
      setName("");
      void qc.invalidateQueries({ queryKey: ["me", "tokens"] });
    },
  });
  const revoke = useMutation({
    mutationFn: (id: string) => unwrap(api.DELETE("/api/v1/me/tokens/{id}", { params: { path: { id } } })),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["me", "tokens"] }),
  });
  return (
    <Panel
      title={t("Access tokens")}
      note={t("For the desktop sync client and your scripts: each acts as you, within what it allows.")}
      flush
    >
      {secret && (
        <div className="token-secret" role="status">
          <KeyRound size={16} aria-hidden />
          <div className="grow">
            <strong>{t("Copy your new token now: it won't be shown again.")}</strong>
            <code>{secret}</code>
            <span className="muted small">{t("Then, on your computer:")}</span>
            <code>
              plm-sync login --server {config.apiBase || window.location.origin} --token {secret.slice(0, 12)}…
            </code>
          </div>
          <button
            className="btn small"
            onClick={() => void navigator.clipboard?.writeText(secret).then(() => setCopied(true))}
            aria-label={t("Copy the token")}
          >
            <Copy size={13} aria-hidden /> {copied ? t("Copied") : t("Copy")}
          </button>
        </div>
      )}
      {tokens && tokens.length > 0 ? (
        <table className="grid" aria-label={t("Access tokens")}>
          <thead>
            <tr>
              <th>{t("Name")}</th>
              <th>{t("Allows")}</th>
              <th>{t("Created")}</th>
              <th>{t("Last used")}</th>
              <th>{t("Expires")}</th>
              <th aria-label={t("Actions")} />
            </tr>
          </thead>
          <tbody>
            {tokens.map((tk) => (
              <tr key={tk.id}>
                <td>
                  <strong>{tk.name}</strong> <code className="muted">{tk.prefix}…</code>
                </td>
                <td>{tk.scopes.includes("write") ? t("Read and change") : t("Read only")}</td>
                <td>
                  <When iso={tk.createdAt} />
                </td>
                <td>{tk.lastUsedAt ? <When iso={tk.lastUsedAt} /> : <span className="muted">{t("Never")}</span>}</td>
                <td>{tk.expiresAt ? <When iso={tk.expiresAt} /> : <span className="muted">{t("Never")}</span>}</td>
                <td>
                  <button
                    className="btn small ghost"
                    aria-label={t("Revoke {name}", { name: tk.name })}
                    onClick={() =>
                      window.confirm(t("Revoke “{name}”? Tools using it stop working.", { name: tk.name })) && revoke.mutate(tk.id)
                    }
                  >
                    <Trash2 size={13} aria-hidden /> {t("Revoke")}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <EmptyState
          icon={<KeyRound size={18} />}
          title={t("No access tokens")}
          hint={t("Make one to sync a workspace with a folder on your computer.")}
        />
      )}
      <form
        className="token-form"
        onSubmit={(e) => {
          e.preventDefault();
          issue.mutate();
        }}
      >
        <input aria-label={t("Token name")} placeholder={t("e.g. Laptop sync")} value={name} onChange={(e) => setName(e.target.value)} />
        <label className="check">
          <input type="checkbox" checked={write} onChange={(e) => setWrite(e.target.checked)} />
          {t("May change things")}
        </label>
        <select aria-label={t("Expires after")} value={days} onChange={(e) => setDays(e.target.value)}>
          <option value="30">{t("30 days")}</option>
          <option value="90">{t("90 days")}</option>
          <option value="365">{t("1 year")}</option>
          <option value="">{t("Never expires")}</option>
        </select>
        <button className="btn primary" type="submit" disabled={!name.trim() || issue.isPending}>
          <KeyRound size={14} aria-hidden /> {t("Make a token")}
        </button>
      </form>
      <ErrorBanner error={issue.error ?? revoke.error} />
    </Panel>
  );
}
