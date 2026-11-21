import { useMutation, useQueryClient } from "@tanstack/react-query";
import { GitBranch } from "lucide-react";
import { useState } from "react";
import { api, unwrap } from "../api/client";
import { useHistory, useMe, useWorkingCopy, type VersionedObject } from "../api/queries";
import { ErrorBanner, Panel, PrincipalName, StateBadge, formatDate } from "../components/common";
import { t } from "../i18n/i18n";

/**
 * One-off versions (roadmap 7.10): versions of a released part made for one order or unit, beside
 * the main line. Making one checks it out to you; you edit it like any working copy.
 */
export function OneOffsPanel({ obj }: { obj: VersionedObject }) {
  const qc = useQueryClient();
  const { data: history } = useHistory(obj.id);
  const [forWhat, setFor] = useState("");
  const make = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/objects/{id}/one-offs", { params: { path: { id: obj.id } }, body: { forWhat } })),
    onSuccess: () => {
      setFor("");
      void qc.invalidateQueries();
    },
  });
  const oneOffs = (history ?? []).filter((h) => h.oneOff);
  return (
    <Panel
      title={t("One-off versions")}
      note={t("Made from a released version for one order or unit; the main line and its configurations don't see them.")}
      flush
    >
      <ErrorBanner error={make.error} />
      {oneOffs.length ? (
        <table className="grid" aria-label={t("One-off versions")}>
          <thead>
            <tr>
              <th>{t("Version")}</th>
              <th>{t("For")}</th>
              <th>{t("State")}</th>
              <th>{t("Comment")}</th>
              <th>{t("Made by")}</th>
              <th>{t("Checked in")}</th>
            </tr>
          </thead>
          <tbody>
            {oneOffs.map((o) => (
              <tr key={o.id}>
                <td className="nowrap version">
                  <GitBranch size={13} aria-hidden /> {o.version}
                </td>
                <td>{o.oneOffFor}</td>
                <td>
                  <StateBadge state={o.state} />
                </td>
                <td>{o.comment ?? <span className="muted">—</span>}</td>
                <td className="nowrap">
                  <PrincipalName id={o.createdBy} />
                </td>
                <td className="nowrap muted">{formatDate(o.checkedInAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <div className="empty small">{t("No one-off versions.")}</div>
      )}
      {!obj.checkedOutBy && (
        <form
          className="row spec-save"
          onSubmit={(e) => {
            e.preventDefault();
            make.mutate();
          }}
        >
          <input
            aria-label={t("One-off for")}
            placeholder={t("What it's for, e.g. Order 4471, or unit 17")}
            value={forWhat}
            onChange={(e) => setFor(e.target.value)}
          />
          <button className="btn small" disabled={!forWhat.trim() || make.isPending}>
            <GitBranch size={13} aria-hidden /> {t("Make a one-off of {version}", { version: obj.latest.version })}
          </button>
        </form>
      )}
    </Panel>
  );
}

/** Says so when your working copy is a one-off, not the next main-line version. */
export function OneOffBanner({ obj }: { obj: VersionedObject }) {
  const { data: me } = useMe();
  const mine = !!me && obj.checkedOutBy === me.user.id;
  const { data: wc } = useWorkingCopy(obj.id, mine);
  if (!mine || !wc?.oneOff) return null;
  return (
    <div className="banner info one-off-banner">
      <GitBranch size={14} aria-hidden />{" "}
      {t("You're editing one-off {version} for {what}. Checking it in keeps it beside the main line, which stays at {latest}.", {
        version: wc.version,
        what: wc.oneOffFor ?? "",
        latest: obj.latest.version,
      })}
    </div>
  );
}
