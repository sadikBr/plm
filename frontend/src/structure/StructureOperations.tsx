import { useMutation, useQuery } from "@tanstack/react-query";
import { GitBranchPlus, Replace } from "lucide-react";
import { useMemo, useState } from "react";
import { api, unwrap } from "../api/client";
import type { VersionedObject } from "../api/queries";
import { Dialog, ErrorBanner, Loading, StateBadge } from "../components/common";
import { t, tn } from "../i18n/i18n";
import { PartPicker } from "./Structure";
import { loadStructure } from "./StructureTree";

/** Structure operations on many parts at once (roadmap 4.14), run as background jobs. */

type Candidate = { id: string; number: string; name: string; version: string; state: string; level: number };

/** Revise a part and the parts under it in one go: by default those released. */
export function ReviseStructureButton({ obj, onStarted }: { obj: VersionedObject; onStarted: (jobId: string) => void }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button className="btn small" onClick={() => setOpen(true)}>
        <GitBranchPlus size={13} aria-hidden /> {t("Revise…")}
      </button>
      {open && (
        <ReviseDialog
          obj={obj}
          onClose={() => setOpen(false)}
          onStarted={(id) => {
            setOpen(false);
            onStarted(id);
          }}
        />
      )}
    </>
  );
}

function ReviseDialog({ obj, onClose, onStarted }: { obj: VersionedObject; onClose: () => void; onStarted: (jobId: string) => void }) {
  const { data, error, isLoading } = useQuery({
    queryKey: ["structure", obj.id, "revise"],
    queryFn: () => loadStructure(obj.id, { type: "LATEST" }, 50),
  });
  // Each part once, at the highest level it appears, the root first.
  const parts = useMemo<Candidate[]>(() => {
    const seen = new Map<string, Candidate>();
    seen.set(obj.id, { id: obj.id, number: obj.number, name: obj.name, version: obj.version, state: obj.latest.state, level: 0 });
    for (const n of data?.nodes ?? []) {
      const c = n.line.child;
      if (!c.readable || seen.has(c.id)) continue;
      seen.set(c.id, {
        id: c.id,
        number: c.number ?? "",
        name: c.name ?? "",
        version: c.version ?? "",
        state: c.state ?? "",
        level: n.level,
      });
    }
    return [...seen.values()];
  }, [data, obj]);
  const [picked, setPicked] = useState<Set<string> | null>(null);
  const chosen = picked ?? new Set(parts.filter((p) => p.state === "RELEASED").map((p) => p.id));
  const toggle = (id: string) => {
    const next = new Set(chosen);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    setPicked(next);
  };
  const start = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/bulk", { body: { operation: "REVISE", objectIds: [...chosen] } })),
    onSuccess: (job) => onStarted(job.id),
  });
  return (
    <Dialog
      open
      wide
      title={t("Revise {number} and its structure", { number: obj.number })}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            {t("Cancel")}
          </button>
          <button className="btn primary" disabled={chosen.size === 0 || start.isPending} onClick={() => start.mutate()}>
            {tn("Revise {n} part", "Revise {n} parts", chosen.size)}
          </button>
        </>
      }
    >
      <p className="muted" style={{ margin: "0 0 8px" }}>
        {t("Each chosen part gets a new revision, in the background; one that can't be revised is reported and the others go on.")}
      </p>
      <ErrorBanner error={error ?? start.error} />
      {isLoading ? (
        <Loading />
      ) : (
        <div className="import-preview">
          <table className="grid" aria-label={t("Parts to revise")}>
            <thead>
              <tr>
                <th className="check-cell">
                  <input
                    type="checkbox"
                    aria-label={t("All")}
                    checked={chosen.size === parts.length}
                    onChange={(e) => setPicked(new Set(e.target.checked ? parts.map((p) => p.id) : []))}
                  />
                </th>
                <th className="num">{t("Level")}</th>
                <th>{t("Number")}</th>
                <th>{t("Name")}</th>
                <th>{t("Version")}</th>
                <th>{t("State")}</th>
              </tr>
            </thead>
            <tbody>
              {parts.map((p) => (
                <tr key={p.id}>
                  <td className="check-cell">
                    <input type="checkbox" aria-label={p.number} checked={chosen.has(p.id)} onChange={() => toggle(p.id)} />
                  </td>
                  <td className="num">{p.level}</td>
                  <td className="mono" style={{ paddingLeft: 8 + p.level * 14 }}>
                    {p.number}
                  </td>
                  <td>{p.name}</td>
                  <td>{p.version}</td>
                  <td>
                    <StateBadge state={p.state} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Dialog>
  );
}

/** Put another part in this one's place in the structures using it. */
export function ReplaceEverywhereButton({ obj, onStarted }: { obj: VersionedObject; onStarted: (jobId: string) => void }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button className="btn small" onClick={() => setOpen(true)}>
        <Replace size={13} aria-hidden /> {t("Replace everywhere…")}
      </button>
      {open && (
        <ReplaceDialog
          obj={obj}
          onClose={() => setOpen(false)}
          onStarted={(id) => {
            setOpen(false);
            onStarted(id);
          }}
        />
      )}
    </>
  );
}

function ReplaceDialog({ obj, onClose, onStarted }: { obj: VersionedObject; onClose: () => void; onStarted: (jobId: string) => void }) {
  const { data, error, isLoading } = useQuery({
    queryKey: ["whereUsed", obj.id, "replace"],
    queryFn: () =>
      unwrap(api.GET("/api/v1/objects/{id}/where-used", { params: { path: { id: obj.id }, query: { spec: "LATEST", depth: 1 } } })),
  });
  const parents = useMemo(() => {
    const seen = new Map<string, NonNullable<typeof data>["nodes"][number]>();
    for (const n of data?.nodes ?? []) if (n.parent.readable && !seen.has(n.parent.id)) seen.set(n.parent.id, n);
    return [...seen.values()];
  }, [data]);
  const [replacement, setReplacement] = useState<VersionedObject | null>(null);
  const [picked, setPicked] = useState<Set<string> | null>(null);
  const chosen = picked ?? new Set(parents.map((p) => p.parent.id));
  const [revise, setRevise] = useState(false);
  const [comment, setComment] = useState("");
  const toggle = (id: string) => {
    const next = new Set(chosen);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    setPicked(next);
  };
  const start = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/api/v1/structure-operations/replace", {
          body: { oldId: obj.id, newId: replacement!.id, parentIds: [...chosen], reviseReleased: revise, comment: comment.trim() || null },
        }),
      ),
    onSuccess: (job) => onStarted(job.id),
  });
  const released = parents.filter((p) => chosen.has(p.parent.id) && p.parent.state === "RELEASED").length;
  return (
    <Dialog
      open
      wide
      title={t("Replace {number} where it's used", { number: obj.number })}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            {t("Cancel")}
          </button>
          <button className="btn primary" disabled={!replacement || chosen.size === 0 || start.isPending} onClick={() => start.mutate()}>
            {tn("Replace in {n} part", "Replace in {n} parts", chosen.size)}
          </button>
        </>
      }
    >
      <div style={{ display: "grid", gap: 12 }}>
        <div className="field">
          <span className="label">{t("Put in its place")}</span>
          {replacement ? (
            <div className="row" style={{ gap: 8 }}>
              <span className="chosen-part">
                <strong className="mono">{replacement.number}</strong> {replacement.name}
              </span>
              <button className="btn small" onClick={() => setReplacement(null)}>
                {t("Change")}
              </button>
            </div>
          ) : (
            <PartPicker
              exclude={obj.id}
              contextId={obj.contextId}
              label={t("Replacement part")}
              allowCreate={false}
              onPick={setReplacement}
            />
          )}
        </div>
        <ErrorBanner error={error ?? start.error} />
        {isLoading ? (
          <Loading />
        ) : parents.length === 0 ? (
          <div className="muted">{t("No part uses it.")}</div>
        ) : (
          <div className="import-preview">
            <table className="grid" aria-label={t("Parts using it")}>
              <thead>
                <tr>
                  <th className="check-cell">
                    <input
                      type="checkbox"
                      aria-label={t("All")}
                      checked={chosen.size === parents.length}
                      onChange={(e) => setPicked(new Set(e.target.checked ? parents.map((p) => p.parent.id) : []))}
                    />
                  </th>
                  <th>{t("Number")}</th>
                  <th>{t("Name")}</th>
                  <th>{t("Version")}</th>
                  <th>{t("State")}</th>
                  <th className="num">{t("Quantity")}</th>
                </tr>
              </thead>
              <tbody>
                {parents.map((n) => (
                  <tr key={n.parent.id}>
                    <td className="check-cell">
                      <input
                        type="checkbox"
                        aria-label={n.parent.number ?? ""}
                        checked={chosen.has(n.parent.id)}
                        onChange={() => toggle(n.parent.id)}
                      />
                    </td>
                    <td className="mono">{n.parent.number}</td>
                    <td>{n.parent.name}</td>
                    <td>{n.parent.version}</td>
                    <td>{n.parent.state && <StateBadge state={n.parent.state} />}</td>
                    <td className="num">{n.quantity}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <label className="check">
          <input type="checkbox" checked={revise} onChange={(e) => setRevise(e.target.checked)} />
          {released > 0
            ? tn(
                "Revise the {n} released part first (otherwise it's left as it is)",
                "Revise the {n} released parts first (otherwise they're left as they are)",
                released,
              )
            : t("Revise parts that can't be changed in their state first")}
        </label>
        <div className="field">
          <label htmlFor="replace-comment">{t("Check-in comment")}</label>
          <input
            id="replace-comment"
            value={comment}
            onChange={(e) => setComment(e.target.value)}
            placeholder={replacement ? t("Replaced {old} by {new}", { old: obj.number, new: replacement.number }) : ""}
          />
        </div>
      </div>
    </Dialog>
  );
}
