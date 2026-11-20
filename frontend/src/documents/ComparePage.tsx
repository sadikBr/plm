import { useQuery } from "@tanstack/react-query";
import { ArrowRight, GitCompare } from "lucide-react";
import { useState } from "react";
import { Link, useSearchParams } from "react-router";
import { api, unwrap, type Schemas } from "../api/client";
import { useTypeAttributes } from "../api/queries";
import { ErrorBanner, Loading, Panel, StateBadge } from "../components/common";
import { PageHeader } from "../components/Layout";
import { formatSize } from "../components/ObjectFiles";
import { pickLabel } from "../components/AttributeForm";

type Comparison = Schemas["Comparison"];
type DiffLine = Schemas["DiffLine"];

const CHANGE: Record<string, [string, string]> = {
  SAME: ["tag", "same"],
  CHANGED: ["tag warn", "changed"],
  ADDED: ["tag allow", "added"],
  REMOVED: ["tag deny", "removed"],
};

/** A value as people read it: quantities with their unit, lists joined, empty as a dash. */
function show(v: unknown): string {
  if (v == null || v === "") return "—";
  if (typeof v === "object" && v !== null && "value" in v) {
    const q = v as { value: unknown; unit?: string };
    return `${String(q.value)}${q.unit ? ` ${q.unit}` : ""}`;
  }
  if (Array.isArray(v)) return v.map(show).join(", ");
  if (typeof v === "boolean") return v ? "Yes" : "No";
  return typeof v === "object" ? JSON.stringify(v) : String(v);
}

/** Two versions side by side (roadmap 3.3): values, files, and the primary file's lines when it's text. */
export function ComparePage() {
  const [params] = useSearchParams();
  const left = params.get("left") ?? "";
  const right = params.get("right") ?? "";
  const leftIteration = params.get("leftIteration") ?? undefined;
  const rightIteration = params.get("rightIteration") ?? undefined;
  const [onlyChanges, setOnlyChanges] = useState(true);
  const { data, error, isLoading } = useQuery({
    queryKey: ["compare", left, leftIteration, right, rightIteration],
    queryFn: () => unwrap(api.GET("/api/v1/compare", { params: { query: { left, right, leftIteration, rightIteration } } })),
    enabled: !!left && !!right,
  });
  const { data: definitions } = useTypeAttributes(data?.right.typeId);
  const label = (name: string) => pickLabel(definitions?.find((d) => d.name === name)?.labels, name);

  if (isLoading) return <Loading />;
  if (error || !data) return <ErrorBanner error={error ?? new Error("Choose two versions to compare")} />;
  const changed = data.attributes.filter((a) => a.changed).length;
  const rows = onlyChanges ? data.attributes.filter((a) => a.changed) : data.attributes;

  return (
    <>
      <PageHeader
        icon={<GitCompare size={18} aria-hidden />}
        title={<span className="nm">Compare</span>}
        meta={
          <span className="sub">
            {changed} value{changed === 1 ? "" : "s"} and {data.files.filter((f) => f.change !== "SAME").length} file(s) differ
          </span>
        }
      />
      <div className="content" style={{ display: "grid", gap: 12 }}>
        <div className="compare-heads">
          <Side v={data.left} />
          <ArrowRight size={20} className="icon-muted" aria-hidden />
          <Side v={data.right} />
        </div>
        <Panel
          title="Attributes"
          flush
          actions={
            <label className="check">
              <input type="checkbox" checked={onlyChanges} onChange={(e) => setOnlyChanges(e.target.checked)} />
              Only differences
            </label>
          }
        >
          {rows.length === 0 ? (
            <p className="empty">{onlyChanges ? "Every value is the same." : "No attributes."}</p>
          ) : (
            <table className="grid compare" aria-label="Attribute differences">
              <thead>
                <tr>
                  <th>Attribute</th>
                  <th>
                    {data.left.number} {data.left.version}
                  </th>
                  <th>
                    {data.right.number} {data.right.version}
                  </th>
                </tr>
              </thead>
              <tbody>
                {rows.map((a) => (
                  <tr key={a.name} className={a.changed ? "changed" : undefined}>
                    <td>{label(a.name)}</td>
                    <td className={a.changed ? "old" : undefined}>{show(a.left)}</td>
                    <td className={a.changed ? "new" : undefined}>{show(a.right)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Panel>
        <Panel title="Files" flush>
          {data.files.length === 0 ? (
            <p className="empty">Neither has files.</p>
          ) : (
            <table className="grid" aria-label="File differences">
              <thead>
                <tr>
                  <th>File</th>
                  <th>Role</th>
                  <th>Change</th>
                  <th>Size</th>
                </tr>
              </thead>
              <tbody>
                {data.files.map((f) => (
                  <tr key={`${f.role}:${f.fileName}`}>
                    <td>{f.fileName}</td>
                    <td className="muted">{f.role === "PRIMARY" ? "Primary" : "Attachment"}</td>
                    <td>
                      <span className={CHANGE[f.change]![0]}>{CHANGE[f.change]![1]}</span>
                    </td>
                    <td className="nowrap muted">
                      {f.leftSize != null ? formatSize(f.leftSize) : "—"} → {f.rightSize != null ? formatSize(f.rightSize) : "—"}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Panel>
        {data.primaryText && <TextDiff diff={data.primaryText} />}
      </div>
    </>
  );
}

function Side({ v }: { v: Comparison["left"] }) {
  return (
    <div className="compare-side">
      <Link to={`/objects/${v.objectId}`}>
        <strong>{v.number}</strong> · {v.name}
      </Link>
      <div className="row" style={{ gap: 6 }}>
        <span className="tag accent version">{v.version}</span>
        <StateBadge state={v.state} />
      </div>
    </div>
  );
}

/** Lines of the primary text files, unchanged runs folded to a few lines of context. */
function TextDiff({ diff }: { diff: Schemas["TextDifference"] }) {
  const [expanded, setExpanded] = useState(false);
  const lines = expanded ? diff.lines : fold(diff.lines, 2);
  return (
    <Panel title={`Lines of ${diff.fileName}`} note={diff.skipped ?? `+${diff.added} −${diff.removed}`} flush>
      {diff.skipped ? null : diff.added + diff.removed === 0 ? (
        <p className="empty">The text is the same.</p>
      ) : (
        <>
          <pre className="text-diff" aria-label="Line differences">
            {lines.map((l, i) =>
              l === null ? (
                <div key={i} className="fold">
                  ⋯
                </div>
              ) : (
                <div key={i} className={l.change.toLowerCase()}>
                  <span className="ln">{l.leftLine ?? ""}</span>
                  <span className="ln">{l.rightLine ?? ""}</span>
                  <span className="mark">{l.change === "ADDED" ? "+" : l.change === "REMOVED" ? "−" : " "}</span>
                  {l.text}
                </div>
              ),
            )}
          </pre>
          <div className="panel-foot">
            <button className="btn small ghost" onClick={() => setExpanded(!expanded)}>
              {expanded ? "Show only the changes" : "Show every line"}
            </button>
          </div>
        </>
      )}
    </Panel>
  );
}

/** Keeps changed lines and [context] lines around them; null marks folded runs. */
function fold(lines: DiffLine[], context: number): (DiffLine | null)[] {
  const keep = lines.map((l) => l.change !== "SAME");
  const near = lines.map((_, i) => keep.slice(Math.max(0, i - context), i + context + 1).some(Boolean));
  const out: (DiffLine | null)[] = [];
  lines.forEach((l, i) => {
    if (near[i]) out.push(l);
    else if (out[out.length - 1] !== null) out.push(null);
  });
  return out;
}
