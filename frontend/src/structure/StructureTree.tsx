import { useQuery } from "@tanstack/react-query";
import { ChevronDown, ChevronRight, ChevronsDownUp, ChevronsUpDown, FileSpreadsheet, Lock } from "lucide-react";
import { useMemo, useRef, useState } from "react";
import { Link } from "react-router";
import { api, unwrap, type Schemas } from "../api/client";
import { useEffectivePreferences, useLifecycles, type VersionedObject } from "../api/queries";
import { ErrorBanner, Loading, StateBadge, TypeIcon, stateLabel } from "../components/common";
import { language, locale, t, tn } from "../i18n/i18n";
import { useUnits } from "./Structure";

// The multi-level structure (roadmap 4.2, 4.3): a tree table under a configuration specification.

type Node = Schemas["StructureNode"];
type SpecType = Schemas["ConfigSpec"]["type"];
export type Spec = { type: SpecType; state?: string; asOf?: string };

const ROW = 32;
const qty = (n: number) => n.toLocaleString(locale(), { maximumFractionDigits: 6 });

function specQuery(spec: Spec, depth: number) {
  return {
    spec: spec.type,
    depth,
    ...(spec.type === "STATE" ? { state: spec.state ?? "RELEASED" } : {}),
    ...(spec.type === "AS_OF" && spec.asOf ? { asOf: new Date(`${spec.asOf}T23:59:59`).toISOString() } : {}),
  };
}

async function loadStructure(id: string, spec: Spec, depth: number) {
  return unwrap(api.GET("/api/v1/objects/{id}/structure", { params: { path: { id }, query: specQuery(spec, depth) } }));
}

/** The specification a structure opens with: the user's or context's `bom.configSpec`, else latest. */
export function useDefaultSpec(contextId: string): Spec {
  const { data } = useEffectivePreferences(contextId);
  return data?.get("bom.configSpec") === "RELEASED" ? { type: "STATE", state: "RELEASED" } : { type: "LATEST" };
}

/** Chooses latest, latest in a state, or as of a date. */
export function SpecPicker({ spec, onChange }: { spec: Spec; onChange: (s: Spec) => void }) {
  const { data: lifecycles } = useLifecycles();
  const states = useMemo(() => {
    const keys = new Map<string, Record<string, string>>();
    for (const l of lifecycles ?? []) for (const s of l.states) if (!keys.has(s.key)) keys.set(s.key, s.labels);
    return [...keys.entries()];
  }, [lifecycles]);
  const value = spec.type === "STATE" ? `STATE:${spec.state ?? "RELEASED"}` : spec.type;
  return (
    <span className="spec-picker">
      <label htmlFor="spec">{t("Show")}</label>
      <select
        id="spec"
        value={value}
        onChange={(e) => {
          const v = e.target.value;
          if (v.startsWith("STATE:")) onChange({ type: "STATE", state: v.slice(6) });
          else if (v === "AS_OF") onChange({ type: "AS_OF", asOf: spec.asOf ?? new Date().toISOString().slice(0, 10) });
          else onChange({ type: "LATEST" });
        }}
      >
        <option value="LATEST">{t("Latest versions")}</option>
        {states.map(([key, labels]) => (
          <option key={key} value={`STATE:${key}`}>
            {t("Latest {state}", { state: labels[language()] ?? labels.en ?? stateLabel(key) })}
          </option>
        ))}
        <option value="AS_OF">{t("As of a date")}</option>
      </select>
      {spec.type === "AS_OF" && (
        <input aria-label={t("Date")} type="date" value={spec.asOf ?? ""} onChange={(e) => onChange({ ...spec, asOf: e.target.value })} />
      )}
    </span>
  );
}

/**
 * The structure under [obj] as an expandable tree. Levels load as they're opened; "Expand all"
 * loads everything at once. Only the rows in view are drawn, so large structures stay quick.
 */
export function StructureTree({ obj, spec }: { obj: VersionedObject; spec: Spec }) {
  const units = useUnits();
  const unitLabel = (key: string) => units.find((u) => u.key === key)?.label ?? key;
  const [all, setAll] = useState(false);
  const top = useQuery({
    queryKey: ["structure", obj.id, spec, all ? 50 : 1],
    queryFn: () => loadStructure(obj.id, spec, all ? 50 : 1),
  });
  // Lines loaded on demand, by the path of the line they're under.
  const [extra, setExtra] = useState<Map<string, Node[]>>(new Map());
  const [open, setOpen] = useState<Set<string>>(new Set());
  const [loading, setLoading] = useState<string | null>(null);
  const [error, setError] = useState<unknown>();
  // In "expand all" mode every line is open except the ones closed since.
  const [closed, setClosed] = useState<Set<string>>(new Set());
  const isOpen = (path: string) => (all ? !closed.has(path) : open.has(path));

  const children = useMemo(() => {
    const m = new Map<string | null, Node[]>();
    for (const n of top.data?.nodes ?? []) m.set(n.parentPath ?? null, [...(m.get(n.parentPath ?? null) ?? []), n]);
    for (const [path, nodes] of extra) m.set(path, nodes);
    return m;
  }, [top.data, extra]);

  const rows = useMemo(() => {
    const out: Node[] = [];
    const walk = (parent: string | null) => {
      for (const n of children.get(parent) ?? []) {
        out.push(n);
        if (all ? !closed.has(n.path) : open.has(n.path)) walk(n.path);
      }
    };
    walk(null);
    return out;
  }, [children, open, closed, all]);

  const toggle = async (n: Node) => {
    if (all) {
      const next = new Set(closed);
      if (next.has(n.path)) next.delete(n.path);
      else next.add(n.path);
      setClosed(next);
      return;
    }
    const next = new Set(open);
    if (next.has(n.path)) {
      next.delete(n.path);
      setOpen(next);
      return;
    }
    if (!children.has(n.path)) {
      setLoading(n.path);
      try {
        const r = await loadStructure(n.line.child.id, spec, 1);
        const lines = r.nodes.map((c) => ({ ...c, level: n.level + 1, path: `${n.path}.${c.path}`, parentPath: n.path }));
        setExtra((m) => new Map(m).set(n.path, lines));
      } catch (e) {
        setError(e);
        return;
      } finally {
        setLoading(null);
      }
    }
    next.add(n.path);
    setOpen(next);
  };

  const expandAll = () => {
    setClosed(new Set());
    setAll(true);
  };
  const collapseAll = () => {
    setAll(false);
    setOpen(new Set());
    setExtra(new Map());
  };

  // Only the rows in view are rendered.
  const scroller = useRef<HTMLDivElement>(null);
  const [scrollTop, setScrollTop] = useState(0);
  const height = Math.min(640, Math.max(ROW * 3, rows.length * ROW + ROW + 2));
  const first = Math.max(0, Math.floor(scrollTop / ROW) - 10);
  const last = Math.min(rows.length, first + Math.ceil(height / ROW) + 20);

  const exportRows = async () => {
    const r = all ? top.data : await loadStructure(obj.id, spec, 50);
    if (!r) return;
    const body = {
      sheetName: obj.number,
      columns: [t("Level"), t("Line"), t("Number"), t("Name"), t("Version"), t("State"), t("Find no."), t("Quantity"), t("Unit")],
      rows: r.nodes.map((n) => [
        n.level,
        n.path,
        n.line.child.number ?? t("(no access)"),
        n.line.child.name ?? "",
        n.line.child.version ?? "",
        n.line.child.state ? stateLabel(n.line.child.state) : "",
        n.line.findNumber ?? "",
        n.line.quantity,
        unitLabel(n.line.unit),
      ]),
    };
    const res = await api.POST("/api/v1/export/xlsx", {
      body,
      parseAs: "blob",
      headers: { Accept: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" },
    });
    if (!res.data) return;
    const url = URL.createObjectURL(res.data as Blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `${obj.number} structure.xlsx`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 10_000);
  };

  if (top.isLoading) return <Loading />;
  return (
    <div className="structure-tree">
      <ErrorBanner error={top.error ?? error} />
      <div className="tree-bar">
        <span className="muted small">
          {top.data?.rootVersion
            ? t("{number} at {version}", { number: obj.number, version: top.data.rootVersion })
            : t("No version of {number} matches", { number: obj.number })}
          {" · "}
          {tn("{n} line", "{n} lines", rows.length)}
          {all && top.data && ` · ${t("loaded in {ms} ms", { ms: top.data.elapsedMs })}`}
        </span>
        <span className="grow" />
        <button className="btn small" onClick={expandAll} disabled={top.isFetching}>
          <ChevronsUpDown size={13} aria-hidden /> {t("Expand all")}
        </button>
        <button className="btn small" onClick={collapseAll}>
          <ChevronsDownUp size={13} aria-hidden /> {t("Collapse all")}
        </button>
        <button className="btn small" onClick={() => void exportRows()} disabled={!top.data?.nodes.length}>
          <FileSpreadsheet size={13} aria-hidden /> {t("Export to Excel")}
        </button>
      </div>
      {top.data?.truncated && (
        <div className="banner warn">{t("This structure is too large to show in full: the lowest levels were left out.")}</div>
      )}
      <div className="tree-scroll" ref={scroller} style={{ height }} onScroll={(e) => setScrollTop(e.currentTarget.scrollTop)}>
        <table className="grid structure tree" aria-label={t("Structure")} aria-rowcount={rows.length}>
          <thead>
            <tr>
              <th>{t("Number")}</th>
              <th>{t("Name")}</th>
              <th>{t("Version")}</th>
              <th>{t("State")}</th>
              <th className="num">{t("Line")}</th>
              <th>{t("Find no.")}</th>
              <th className="num">{t("Quantity")}</th>
              <th>{t("Unit")}</th>
            </tr>
          </thead>
          <tbody>
            {first > 0 && <tr style={{ height: first * ROW }} aria-hidden />}
            {rows.slice(first, last).map((n) => {
              const c = n.line.child;
              const expandable = c.readable && c.hasChildren;
              return (
                <tr key={n.path} aria-level={n.level} style={{ height: ROW }}>
                  <td className="nowrap tree-cell" style={{ paddingLeft: 8 + (n.level - 1) * 18 }}>
                    {expandable ? (
                      <button
                        className="tree-toggle"
                        aria-label={
                          isOpen(n.path)
                            ? t("Collapse {number}", { number: c.number ?? "" })
                            : t("Expand {number}", { number: c.number ?? "" })
                        }
                        aria-expanded={isOpen(n.path)}
                        onClick={() => void toggle(n)}
                      >
                        {loading === n.path ? (
                          <span className="spinner small" />
                        ) : isOpen(n.path) ? (
                          <ChevronDown size={13} />
                        ) : (
                          <ChevronRight size={13} />
                        )}
                      </button>
                    ) : (
                      <span className="tree-spacer" />
                    )}
                    {c.typeId ? <TypeIcon typeId={c.typeId} /> : <Lock size={14} className="icon-muted" />}{" "}
                    {c.readable ? (
                      <Link className="num-link" to={`/objects/${c.id}?tab=structure`}>
                        {c.number}
                      </Link>
                    ) : (
                      <span className="muted">{t("(no access)")}</span>
                    )}
                  </td>
                  <td className="ellipsis">{c.name}</td>
                  <td className="version">{c.version ?? (c.readable ? <span className="muted">{t("none")}</span> : null)}</td>
                  <td>{c.state && <StateBadge state={c.state} />}</td>
                  <td className="num muted">{n.line.lineNumber}</td>
                  <td>{n.line.findNumber}</td>
                  <td className="num">{qty(n.line.quantity)}</td>
                  <td>{unitLabel(n.line.unit)}</td>
                </tr>
              );
            })}
            {last < rows.length && <tr style={{ height: (rows.length - last) * ROW }} aria-hidden />}
            {rows.length === 0 && (
              <tr>
                <td colSpan={8} className="empty">
                  {t("This part uses no other parts.")}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
