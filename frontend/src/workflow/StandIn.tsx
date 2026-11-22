import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { UserRoundCheck } from "lucide-react";
import { useState } from "react";
import { api, unwrap } from "../api/client";
import { ErrorBanner, Panel, PrincipalName } from "../components/common";
import { t } from "../i18n/i18n";

/** Who gets my new tasks while I'm away (roadmap 5.4). */
export function StandInPanel() {
  const qc = useQueryClient();
  const { data } = useQuery({ queryKey: ["standIn"], queryFn: () => unwrap(api.GET("/api/v1/me/stand-in")) });
  const [username, setUsername] = useState("");
  const [from, setFrom] = useState("");
  const [until, setUntil] = useState("");
  const done = () => void qc.invalidateQueries({ queryKey: ["standIn"] });
  const save = useMutation({
    mutationFn: () =>
      unwrap(api.PUT("/api/v1/me/stand-in", { body: { username: username.trim(), from: from || null, until: until || null } })),
    onSuccess: done,
  });
  const clear = useMutation({ mutationFn: () => unwrap(api.DELETE("/api/v1/me/stand-in")), onSuccess: done });
  return (
    <Panel title={t("While I'm away")} note={t("New tasks for you go to your stand-in instead; tasks you already have stay yours.")}>
      {data ? (
        <div className="standin-set">
          <span className="standin-icon">
            <UserRoundCheck size={16} aria-hidden />
          </span>
          <span>
            {t("Your stand-in is")} <strong>{<PrincipalName id={data.standIn} />}</strong>
            {data.from || data.until ? ` · ${data.from ?? "…"} – ${data.until ?? "…"}` : ` · ${t("until you remove it")}`}
          </span>
          <button className="btn small" onClick={() => clear.mutate()} disabled={clear.isPending}>
            {t("Remove")}
          </button>
        </div>
      ) : (
        <form
          className="standin-form"
          onSubmit={(e) => {
            e.preventDefault();
            save.mutate();
          }}
        >
          <div className="field">
            <label htmlFor="standin-user">{t("Stand-in (username)")}</label>
            <input id="standin-user" value={username} onChange={(e) => setUsername(e.target.value)} placeholder="bob" />
          </div>
          <div className="field">
            <label htmlFor="standin-from">{t("From")}</label>
            <input id="standin-from" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="standin-until">{t("Until")}</label>
            <input id="standin-until" type="date" value={until} onChange={(e) => setUntil(e.target.value)} />
          </div>
          <button className="btn primary" type="submit" disabled={!username.trim() || save.isPending}>
            <UserRoundCheck size={14} aria-hidden /> {t("Set my stand-in")}
          </button>
        </form>
      )}
      <ErrorBanner error={save.error ?? clear.error} />
    </Panel>
  );
}
