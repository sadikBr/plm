import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Archive, Lock, PackageSearch, Snowflake, Trash2 } from "lucide-react";
import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { api, unwrap, type Schemas } from "../api/client";
import type { VersionedObject } from "../api/queries";
import { useClipboard } from "../components/Clipboard";
import { Dialog, ErrorBanner, Loading, Panel, PrincipalName, StateBadge, TypeIcon, formatDate } from "../components/common";
import { PageHeader } from "../components/Layout";
import { t, tn } from "../i18n/i18n";
import type { Spec } from "./StructureTree";

// Baselines and collecting related objects (roadmap 4.10).

type Collected = Schemas["CollectedObject"];

export const useBaselines = (containing?: string) =>
  useQuery({
    queryKey: ["baselines", containing ?? "all"],
    queryFn: () => unwrap(api.GET("/api/v1/baselines", { params: { query: containing ? { containing } : {} } })),
  });

const RELATION = (r: string) =>
  r === "TOP"
    ? t("Top")
    : r === "STRUCTURE"
      ? t("In the structure")
      : r === "DESCRIBED_BY"
        ? t("Described by")
        : r === "REFERENCES"
          ? t("References")
          : r;

/** "Collect…": the part, its structure under the shown specification and its documents. */
export function CollectButton({ obj, spec }: { obj: VersionedObject; spec: Spec }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button className="btn small" onClick={() => setOpen(true)}>
        <PackageSearch size={13} aria-hidden /> {t("Collect…")}
      </button>
      {open && <CollectDialog obj={obj} spec={spec} onClose={() => setOpen(false)} />}
    </>
  );
}

function CollectDialog({ obj, spec, onClose }: { obj: VersionedObject; spec: Spec; onClose: () => void }) {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const clipboard = useClipboard();
  const [documents, setDocuments] = useState(true);
  const query = {
    spec: spec.type === "AS_OF" ? "LATEST" : spec.type,
    ...(spec.type === "STATE" ? { state: spec.state } : {}),
    ...(spec.type === "BASELINE" ? { baselineId: spec.baselineId } : {}),
    documents,
  };
  const { data, error, isLoading } = useQuery({
    queryKey: ["collect", obj.id, query],
    queryFn: () => unwrap(api.GET("/api/v1/objects/{id}/collect", { params: { path: { id: obj.id }, query: query as never } })),
  });
  const [unchecked, setUnchecked] = useState<Set<string>>(new Set());
  const chosen = (data ?? []).filter((c) => !unchecked.has(c.item.id));
  const { data: open } = useBaselines();
  const openOnes = (open ?? []).filter((b) => b.status === "OPEN");
  const [target, setTarget] = useState("");
  const [name, setName] = useState(`${obj.number} ${obj.name}`);
  const toBaseline = useMutation({
    mutationFn: async () => {
      const id = target || (await unwrap(api.POST("/api/v1/baselines", { body: { name, contextId: obj.contextId } }))).id;
      return unwrap(
        api.POST("/api/v1/baselines/{id}/members", {
          params: { path: { id } },
          body: { iterationIds: chosen.map((c) => c.item.iterationId!), masterIds: [] },
        }),
      );
    },
    onSuccess: (b) => {
      void qc.invalidateQueries({ queryKey: ["baselines"] });
      onClose();
      navigate(`/baselines/${b.id}`);
    },
  });
  const toggle = (id: string) => {
    const next = new Set(unchecked);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    setUnchecked(next);
  };
  return (
    <Dialog
      open
      wide
      title={t("Collect {number} and what goes with it", { number: obj.number })}
      onClose={onClose}
      footer={
        <>
          <button
            className="btn"
            disabled={!chosen.length}
            onClick={() => {
              chosen.forEach((c) =>
                clipboard.add({ id: c.item.id, number: c.item.number ?? "", name: c.item.name ?? "", version: c.item.version ?? "" }),
              );
              onClose();
            }}
          >
            {tn("Put {n} on the clipboard", "Put {n} on the clipboard", chosen.length)}
          </button>
          <select aria-label={t("Baseline")} value={target} onChange={(e) => setTarget(e.target.value)}>
            <option value="">{t("A new baseline…")}</option>
            {openOnes.map((b) => (
              <option key={b.id} value={b.id}>
                {b.number} · {b.name}
              </option>
            ))}
          </select>
          {!target && <input aria-label={t("New baseline name")} value={name} onChange={(e) => setName(e.target.value)} />}
          <button
            className="btn primary"
            disabled={!chosen.length || toBaseline.isPending || (!target && !name.trim())}
            onClick={() => toBaseline.mutate()}
          >
            <Archive size={14} aria-hidden /> {tn("Add {n} to the baseline", "Add {n} to the baseline", chosen.length)}
          </button>
        </>
      }
    >
      <label className="row small" style={{ gap: 6, marginBottom: 8 }}>
        <input type="checkbox" checked={documents} onChange={(e) => setDocuments(e.target.checked)} />{" "}
        {t("Include the documents linked to the parts")}
      </label>
      <ErrorBanner error={error ?? toBaseline.error} />
      {isLoading ? <Loading /> : <CollectedTable rows={data ?? []} unchecked={unchecked} onToggle={toggle} />}
    </Dialog>
  );
}

function CollectedTable({ rows, unchecked, onToggle }: { rows: Collected[]; unchecked?: Set<string>; onToggle?: (id: string) => void }) {
  return (
    <div className="grid-wrap" style={{ maxHeight: 420 }}>
      <table className="grid" aria-label={t("Collected objects")}>
        <thead>
          <tr>
            {onToggle && <th className="check-cell" />}
            <th className="icon-cell" />
            <th>{t("Number")}</th>
            <th>{t("Name")}</th>
            <th>{t("Version")}</th>
            <th>{t("State")}</th>
            <th>{t("Why")}</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((c) => (
            <tr key={c.item.id}>
              {onToggle && (
                <td className="check-cell">
                  <input
                    type="checkbox"
                    aria-label={t("Include {number}", { number: c.item.number ?? "" })}
                    checked={!unchecked?.has(c.item.id)}
                    onChange={() => onToggle(c.item.id)}
                  />
                </td>
              )}
              <td className="icon-cell">{c.item.typeId ? <TypeIcon typeId={c.item.typeId} /> : <Lock size={14} />}</td>
              <td className="nowrap">
                <Link className="num-link" to={`/objects/${c.item.id}`}>
                  {c.item.number}
                </Link>
              </td>
              <td>{c.item.name}</td>
              <td className="version">{c.item.version}</td>
              <td>{c.item.state && <StateBadge state={c.item.state} />}</td>
              <td className="muted">{RELATION(c.relation)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** All baselines, and one baseline's members. */
export function BaselinesPage() {
  const { id } = useParams();
  const { data, isLoading } = useBaselines();
  return (
    <>
      <PageHeader
        icon={<Archive size={18} className="icon-muted" aria-hidden />}
        title={<span className="nm">{t("Baselines")}</span>}
        meta={<span className="sub">{t("Frozen sets of exact versions: what was built, shipped or approved")}</span>}
      />
      <div className="content reports-layout">
        <Panel title={t("Baselines")} flush>
          {isLoading ? (
            <Loading />
          ) : !data?.length ? (
            <p className="empty">{t("No baselines yet: collect a structure to make one.")}</p>
          ) : (
            <ul className="report-list" aria-label={t("Baselines")}>
              {data.map((b) => (
                <li key={b.id} className={b.id === id ? "selected" : undefined}>
                  <Link to={`/baselines/${b.id}`}>
                    {b.number} · {b.name}
                  </Link>
                  {b.status === "FROZEN" && <Snowflake size={12} className="icon-muted" aria-label={t("Frozen")} />}
                </li>
              ))}
            </ul>
          )}
        </Panel>
        {id ? <BaselineView key={id} id={id} /> : !isLoading && <p className="empty">{t("Choose a baseline.")}</p>}
      </div>
    </>
  );
}

function BaselineView({ id }: { id: string }) {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const baseline = useQuery({
    queryKey: ["baseline", id],
    queryFn: () => unwrap(api.GET("/api/v1/baselines/{id}", { params: { path: { id } } })),
  });
  const members = useQuery({
    queryKey: ["baselineMembers", id],
    queryFn: () => unwrap(api.GET("/api/v1/baselines/{id}/members", { params: { path: { id } } })),
  });
  const refresh = () => {
    void qc.invalidateQueries({ queryKey: ["baseline", id] });
    void qc.invalidateQueries({ queryKey: ["baselines"] });
    void qc.invalidateQueries({ queryKey: ["baselineMembers", id] });
  };
  const freeze = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/baselines/{id}/freeze", { params: { path: { id } } })),
    onSuccess: refresh,
  });
  const remove = useMutation({
    mutationFn: () => unwrap(api.DELETE("/api/v1/baselines/{id}", { params: { path: { id } } })),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["baselines"] });
      navigate("/baselines");
    },
  });
  const removeMember = useMutation({
    mutationFn: (masterId: string) =>
      unwrap(
        api.POST("/api/v1/baselines/{id}/members/remove", { params: { path: { id } }, body: { masterIds: [masterId], iterationIds: [] } }),
      ),
    onSuccess: refresh,
  });
  const b = baseline.data;
  if (!b) return baseline.error ? <ErrorBanner error={baseline.error} /> : <Loading />;
  const open = b.status === "OPEN";
  return (
    <Panel
      title={`${b.number} · ${b.name}`}
      note={open ? t("Open: its members can still change") : t("Frozen on {date}", { date: formatDate(b.frozenAt) })}
      actions={
        open ? (
          <>
            <button
              className="btn small"
              onClick={() => window.confirm(t("Freeze {number}? It can't change afterwards.", { number: b.number })) && freeze.mutate()}
            >
              <Snowflake size={12} aria-hidden /> {t("Freeze")}
            </button>
            <button
              className="icon-btn"
              aria-label={t("Delete the baseline")}
              onClick={() => window.confirm(t("Delete {number}?", { number: b.number })) && remove.mutate()}
            >
              <Trash2 size={13} />
            </button>
          </>
        ) : undefined
      }
      flush
    >
      <ErrorBanner error={members.error ?? freeze.error ?? remove.error ?? removeMember.error} />
      <div className="tree-bar muted small">
        {tn("{n} object", "{n} objects", b.members)} · {t("made {date} by", { date: formatDate(b.createdAt) })}{" "}
        <PrincipalName id={b.createdBy} />
        {b.description && <> · {b.description}</>}
      </div>
      <div className="grid-wrap">
        <table className="grid" aria-label={t("Baseline members")}>
          <thead>
            <tr>
              <th className="icon-cell" />
              <th>{t("Number")}</th>
              <th>{t("Name")}</th>
              <th>{t("Version")}</th>
              <th>{t("State")}</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {members.data?.map((m) => (
              <tr key={m.item.id}>
                <td className="icon-cell">{m.item.typeId && <TypeIcon typeId={m.item.typeId} />}</td>
                <td className="nowrap">
                  <Link className="num-link" to={`/objects/${m.item.id}?tab=structure&baseline=${b.id}`}>
                    {m.item.number}
                  </Link>
                </td>
                <td>{m.item.name}</td>
                <td className="version">{m.item.version}</td>
                <td>{m.item.state && <StateBadge state={m.item.state} />}</td>
                <td style={{ width: 1 }}>
                  {open && (
                    <button
                      className="icon-btn"
                      aria-label={t("Take {number} out", { number: m.item.number ?? "" })}
                      onClick={() => removeMember.mutate(m.item.id)}
                    >
                      <Trash2 size={13} />
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Panel>
  );
}
