import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { HardDrive, Plus, RefreshCw, Trash2 } from "lucide-react";
import { useState } from "react";
import { api, unwrap } from "../api/client";
import { ErrorBanner, Loading, Panel, When } from "../components/common";
import { t } from "../i18n/i18n";

/** My preferences: which file site downloads come from (roadmap 9.10). */
export function FileSitePreference() {
  const qc = useQueryClient();
  const { data } = useQuery({ queryKey: ["myFileSite"], queryFn: () => unwrap(api.GET("/api/v1/me/file-site")) });
  const save = useMutation({
    mutationFn: (siteId: string | null) => unwrap(api.PUT("/api/v1/me/file-site", { body: { siteId } })),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["myFileSite"] }),
  });
  const choices = (data?.choices ?? []) as { id: string; name: string }[];
  if (!choices.length) return null;
  return (
    <Panel title={t("File site")} note={t("Downloads come from the copy nearest you, when it has the file")}>
      <ErrorBanner error={save.error} />
      <select
        aria-label={t("File site")}
        value={(data?.siteId as string | null) ?? ""}
        onChange={(e) => save.mutate(e.target.value || null)}
      >
        <option value="">{t("Main vault")}</option>
        {choices.map((c) => (
          <option key={c.id} value={c.id}>
            {c.name}
          </option>
        ))}
      </select>
    </Panel>
  );
}

/** Administration → File sites: copies of the vault near remote offices. */
export function FileSitesAdmin() {
  const qc = useQueryClient();
  const { data, isLoading } = useQuery({
    queryKey: ["fileSites"],
    queryFn: () => unwrap(api.GET("/api/v1/file-sites")),
    refetchInterval: 5000,
  });
  const [name, setName] = useState("");
  const [location, setLocation] = useState("");
  const [baseUrl, setBaseUrl] = useState("");
  const refresh = () => void qc.invalidateQueries({ queryKey: ["fileSites"] });
  const create = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/file-sites", { body: { name, location, baseUrl: baseUrl || null, active: true } })),
    onSuccess: () => {
      setName("");
      setLocation("");
      setBaseUrl("");
      refresh();
    },
  });
  const replicate = useMutation({
    mutationFn: (id: string) => unwrap(api.POST("/api/v1/file-sites/{id}/replicate", { params: { path: { id } } })),
    onSuccess: refresh,
  });
  const remove = useMutation({
    mutationFn: (id: string) => unwrap(api.DELETE("/api/v1/file-sites/{id}", { params: { path: { id } } })),
    onSuccess: refresh,
  });
  if (isLoading) return <Loading />;
  return (
    <Panel title={t("File sites")} note={t("New files are copied to every active site; people choose their site in My preferences")} flush>
      <ErrorBanner error={create.error ?? replicate.error ?? remove.error} />
      <table className="grid" aria-label={t("File sites")}>
        <thead>
          <tr>
            <th>{t("Site")}</th>
            <th>{t("Copies kept in")}</th>
            <th>{t("Copied")}</th>
            <th>{t("Since")}</th>
            <th aria-label={t("Actions")} />
          </tr>
        </thead>
        <tbody>
          {data?.map((s) => {
            const total = s.ready + s.missing;
            const pct = total ? Math.round((s.ready / total) * 100) : 100;
            return (
              <tr key={s.id}>
                <td>
                  <HardDrive size={13} aria-hidden className="muted" /> <strong>{s.name}</strong>
                  {s.baseUrl && <div className="muted small">{s.baseUrl}</div>}
                </td>
                <td>
                  <code className="small">{s.location}</code>
                </td>
                <td className="site-sync">
                  <span className="site-bar" aria-hidden>
                    <span style={{ width: `${pct}%` }} />
                  </span>
                  <span className="small">
                    {t("{ready} of {total} files", { ready: s.ready.toLocaleString(), total: total.toLocaleString() })}
                    {s.failed > 0 && <span className="tag deny"> {t("{n} failed", { n: s.failed })}</span>}
                  </span>
                </td>
                <td className="small">
                  <When iso={s.createdAt} />
                </td>
                <td className="nowrap">
                  <button className="btn small" disabled={s.missing === 0 || replicate.isPending} onClick={() => replicate.mutate(s.id)}>
                    <RefreshCw size={12} aria-hidden /> {t("Copy what's missing")}
                  </button>{" "}
                  <button className="icon-btn" aria-label={t("Delete")} onClick={() => remove.mutate(s.id)}>
                    <Trash2 size={13} />
                  </button>
                </td>
              </tr>
            );
          })}
          <tr>
            <td>
              <input aria-label={t("Name")} placeholder={t("e.g. Shenzhen")} value={name} onChange={(e) => setName(e.target.value)} />
            </td>
            <td>
              <input
                aria-label={t("Directory")}
                placeholder="/mnt/plm-shenzhen"
                value={location}
                onChange={(e) => setLocation(e.target.value)}
              />
            </td>
            <td colSpan={2}>
              <input
                aria-label={t("Its file server (optional)")}
                placeholder={t("Its file server (optional), https://…")}
                value={baseUrl}
                onChange={(e) => setBaseUrl(e.target.value)}
              />
            </td>
            <td>
              <button className="btn small" disabled={!name.trim() || !location.trim() || create.isPending} onClick={() => create.mutate()}>
                <Plus size={13} aria-hidden /> {t("Add")}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </Panel>
  );
}
