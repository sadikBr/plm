import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "react-router";
import { api, unwrap, type Schemas } from "../api/client";
import { useLifecycles, type AttributeDefinition } from "../api/queries";
import { pickLabel } from "../components/AttributeForm";
import { Dialog, ErrorBanner, Loading, PrincipalName, StateBadge, TypeIcon } from "../components/common";
import { ReferenceInput } from "../components/References";
import { t, tn } from "../i18n/i18n";

type Reason = Schemas["CollectedModel"]["reason"];
type Operation = Schemas["CollectorRequest"]["operation"];

const CHANGE_NOTICE = {
  name: "notice",
  dataType: "REFERENCE",
  referenceTypeId: "00000000-0000-7000-8000-000000000106",
  labels: { en: "Change notice" },
} as unknown as AttributeDefinition;

function reasonLabel(r: Reason): string {
  switch (r) {
    case "SELF":
      return t("this model");
    case "REQUIRED":
      return t("needed");
    case "OPTIONAL":
      return t("optional");
    case "DRAWING":
      return t("drawing");
    case "PARENT":
      return t("uses it");
  }
}

/** The collector (roadmap 6.2): a model with what goes with it, and one operation on the chosen ones. */
export function CollectorDialog({ objectId, onClose }: { objectId: string; onClose: () => void }) {
  const qc = useQueryClient();
  const [dependencies, setDependencies] = useState<"NONE" | "REQUIRED" | "ALL">("REQUIRED");
  const [drawings, setDrawings] = useState(true);
  const [parents, setParents] = useState(false);
  const [unticked, setUnticked] = useState<Set<string>>(new Set());
  const [comment, setComment] = useState("");
  const [state, setState] = useState("");
  const [notice, setNotice] = useState("");
  const { data: lifecycles } = useLifecycles();
  const options = { dependencies, drawings, parents };
  const { data, error, isLoading } = useQuery({
    queryKey: ["collect", objectId, options],
    queryFn: () => unwrap(api.POST("/api/v1/objects/{id}/cad/collect", { params: { path: { id: objectId } }, body: options })),
  });
  const chosen = (data ?? []).filter((c) => !unticked.has(c.model.id)).map((c) => c.model.id);
  const refresh = () => {
    void qc.invalidateQueries({ queryKey: ["collect", objectId] });
    void qc.invalidateQueries({ queryKey: ["object"] });
    void qc.invalidateQueries({ queryKey: ["cadTree"] });
  };
  const run = useMutation({
    mutationFn: (operation: Operation) =>
      unwrap(
        api.POST("/api/v1/cad/collector", {
          body: { operation, ids: chosen, comment: comment.trim() || null, state: operation === "SET_STATE" ? state : null },
        }),
      ).then((r) => tn("{n} model done.", "{n} models done.", r.filter((x) => x.changed).length)),
    onSuccess: refresh,
  });
  const toChange = useMutation({
    mutationFn: async () => {
      for (const targetId of chosen) {
        await unwrap(
          api.POST("/api/v1/changes/{id}/links", { params: { path: { id: notice } }, body: { targetId, role: "RESULTING" } }),
        ).catch((e: unknown) => {
          if ((e as { status?: number }).status !== 409) throw e; // already there
        });
      }
      return tn("{n} model added to the change notice as a result.", "{n} models added to the change notice as results.", chosen.length);
    },
  });
  const states = new Map<string, string>();
  for (const lc of lifecycles ?? []) for (const s of lc.states) if (!states.has(s.key)) states.set(s.key, pickLabel(s.labels, s.key));
  const busy = run.isPending || toChange.isPending;
  return (
    <Dialog
      open
      title={t("Collect")}
      onClose={onClose}
      wide
      footer={
        <button className="btn" onClick={onClose}>
          {t("Close")}
        </button>
      }
    >
      <div className="row collector-options" style={{ gap: 16, flexWrap: "wrap" }}>
        <label className="check-inline">
          {t("Dependencies")}{" "}
          <select value={dependencies} onChange={(e) => setDependencies(e.target.value as typeof dependencies)}>
            <option value="NONE">{t("none")}</option>
            <option value="REQUIRED">{t("needed ones")}</option>
            <option value="ALL">{t("all, optional too")}</option>
          </select>
        </label>
        <label className="check">
          <input type="checkbox" checked={drawings} onChange={(e) => setDrawings(e.target.checked)} />
          {t("Their drawings")}
        </label>
        <label className="check">
          <input type="checkbox" checked={parents} onChange={(e) => setParents(e.target.checked)} />
          {t("Assemblies using it")}
        </label>
      </div>
      <ErrorBanner error={error} />
      {isLoading ? (
        <Loading />
      ) : (
        <table className="grid collected" aria-label={t("Collected models")}>
          <thead>
            <tr>
              <th />
              <th>{t("Model")}</th>
              <th>{t("Why")}</th>
              <th>{t("Version")}</th>
              <th>{t("State")}</th>
              <th>{t("Checked out by")}</th>
            </tr>
          </thead>
          <tbody>
            {(data ?? []).map((c) => (
              <tr key={c.model.id}>
                <td>
                  <input
                    type="checkbox"
                    aria-label={c.model.number ?? ""}
                    checked={!unticked.has(c.model.id)}
                    onChange={() =>
                      setUnticked((s) => {
                        const n = new Set(s);
                        if (n.has(c.model.id)) n.delete(c.model.id);
                        else n.add(c.model.id);
                        return n;
                      })
                    }
                  />
                </td>
                <td>
                  {c.model.typeId && <TypeIcon typeId={c.model.typeId} />} <Link to={`/objects/${c.model.id}`}>{c.model.number}</Link>{" "}
                  {c.model.name}
                </td>
                <td>
                  <span className={c.reason === "SELF" ? "tag accent" : "tag"}>{reasonLabel(c.reason)}</span>
                </td>
                <td>{c.model.version}</td>
                <td>{c.model.state && <StateBadge state={c.model.state} />}</td>
                <td>{c.checkedOutBy ? <PrincipalName id={c.checkedOutBy} /> : ""}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <div className="collector-actions">
        <div className="row" style={{ gap: 8, flexWrap: "wrap" }}>
          <span className="muted">{t("Chosen: {n}", { n: chosen.length })}</span>
          <button className="btn small" disabled={busy || !chosen.length} onClick={() => run.mutate("CHECK_OUT")}>
            {t("Check out")}
          </button>
          <button className="btn small" disabled={busy || !chosen.length} onClick={() => run.mutate("UNDO_CHECK_OUT")}>
            {t("Undo check-out")}
          </button>
          <button className="btn small" disabled={busy || !chosen.length} onClick={() => run.mutate("REVISE")}>
            {t("Revise")}
          </button>
        </div>
        <div className="row" style={{ gap: 8, flexWrap: "wrap" }}>
          <input
            aria-label={t("Comment")}
            placeholder={t("Comment for check-in or state")}
            value={comment}
            onChange={(e) => setComment(e.target.value)}
          />
          <button className="btn small primary" disabled={busy || !chosen.length} onClick={() => run.mutate("CHECK_IN")}>
            {t("Check in")}
          </button>
          <select aria-label={t("State")} value={state} onChange={(e) => setState(e.target.value)}>
            <option value="">{t("State…")}</option>
            {[...states.entries()].map(([k, label]) => (
              <option key={k} value={k}>
                {label}
              </option>
            ))}
          </select>
          <button className="btn small" disabled={busy || !chosen.length || !state} onClick={() => run.mutate("SET_STATE")}>
            {t("Set state")}
          </button>
        </div>
        <div className="row" style={{ gap: 8, alignItems: "flex-end" }}>
          <div style={{ flex: 1 }}>
            <ReferenceInput
              id="col-notice"
              def={CHANGE_NOTICE}
              value={notice || undefined}
              onChange={(v) => setNotice(v ? String(v) : "")}
            />
          </div>
          <button className="btn small" disabled={busy || !chosen.length || !notice} onClick={() => toChange.mutate()}>
            {t("Add to the change notice")}
          </button>
        </div>
        <span className="hint">{t("Releasing goes through a change notice: add the models to one as its results.")}</span>
      </div>
      <ErrorBanner error={run.error ?? toChange.error} />
      {(run.data ?? toChange.data) && <div className="banner ok">{run.data ?? toChange.data}</div>}
    </Dialog>
  );
}
