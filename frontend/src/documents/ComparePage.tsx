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
import { t, tn } from "../i18n/i18n";

type Comparison = Schemas["Comparison"];
type DiffLine = Schemas["DiffLine"];

/** A file's change: its tag's class and label. */
function changeTag(change: string): [string, string] {
  switch (change) {
    case "SAME":
      return ["tag", t("same")];
    case "CHANGED":
      return ["tag warn", t("changed")];
    case "ADDED":
      return ["tag allow", t("added")];
    case "REMOVED":
      return ["tag deny", t("removed")];
    default:
      return ["tag", change.toLowerCase()];
  }
}

/** A value as people read it: quantities with their unit, lists joined, empty as a dash. */
function show(v: unknown): string {
  if (v == null || v === "") return "—";
  if (typeof v === "object" && v !== null && "value" in v) {
    const q = v as { value: unknown; unit?: string };
    return `${String(q.value)}${q.unit ? ` ${q.unit}` : ""}`;
  }
  if (Array.isArray(v)) return v.map(show).join(", ");
  if (typeof v === "boolean") return v ? t("Yes") : t("No");
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
  if (error || !data) return <ErrorBanner error={error ?? new Error(t("Choose two versions to compare"))} />;
  const changed = data.attributes.filter((a) => a.changed).length;
  const rows = onlyChanges ? data.attributes.filter((a) => a.changed) : data.attributes;

  return (
    <>
      <PageHeader
        icon={<GitCompare size={18} aria-hidden />}
        title={<span className="nm">{t("Compare")}</span>}
        meta={
          <span className="sub">
            {tn("{n} value and {files} file(s) differ", "{n} values and {files} file(s) differ", changed, {
              files: data.files.filter((f) => f.change !== "SAME").length,
            })}
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
          title={t("Attributes")}
          flush
          actions={
            <label className="check">
              <input type="checkbox" checked={onlyChanges} onChange={(e) => setOnlyChanges(e.target.checked)} />
              {t("Only differences")}
            </label>
          }
        >
          {rows.length === 0 ? (
            <p className="empty">{onlyChanges ? t("Every value is the same.") : t("No attributes.")}</p>
          ) : (
            <table className="grid compare" aria-label={t("Attribute differences")}>
              <thead>
                <tr>
                  <th>{t("Attribute")}</th>
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
        <Panel title={t("Files")} flush>
          {data.files.length === 0 ? (
            <p className="empty">{t("Neither has files.")}</p>
          ) : (
            <table className="grid" aria-label={t("File differences")}>
              <thead>
                <tr>
                  <th>{t("File")}</th>
                  <th>{t("Role")}</th>
                  <th>{t("Change")}</th>
                  <th>{t("Size")}</th>
                </tr>
              </thead>
              <tbody>
                {data.files.map((f) => (
                  <tr key={`${f.role}:${f.fileName}`}>
                    <td>{f.fileName}</td>
                    <td className="muted">{f.role === "PRIMARY" ? t("Primary") : t("Attachment")}</td>
                    <td>
                      <span className={changeTag(f.change)[0]}>{changeTag(f.change)[1]}</span>
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
    <Panel title={t("Lines of {file}", { file: diff.fileName })} note={diff.skipped ?? `+${diff.added} −${diff.removed}`} flush>
      {diff.skipped ? null : diff.added + diff.removed === 0 ? (
        <p className="empty">{t("The text is the same.")}</p>
      ) : (
        <>
          <pre className="text-diff" aria-label={t("Line differences")}>
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
              {expanded ? t("Show only the changes") : t("Show every line")}
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
