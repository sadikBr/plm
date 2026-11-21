import { useQuery } from "@tanstack/react-query";
import { FileSpreadsheet, GitCompareArrows } from "lucide-react";
import { useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router";
import { api, unwrap, type Schemas } from "../api/client";
import { useHistory, useObject, useObjectSearch, useTypes, type VersionedObject } from "../api/queries";
import { Dialog, ErrorBanner, Loading, Panel, TypeIcon } from "../components/common";
import { PageHeader } from "../components/Layout";
import { locale, t } from "../i18n/i18n";
import { formatDesignators } from "./designators";
import { useUnits } from "./Structure";

// BOM compare (roadmap 4.9).

type Row = Schemas["CompareRow"];
type Line = Schemas["CompareLine"];
const qty = (n: number) => n.toLocaleString(locale(), { maximumFractionDigits: 6 });

const STATUS = (s: Row["status"]) =>
  s === "ADDED" ? t("Added") : s === "REMOVED" ? t("Removed") : s === "CHANGED" ? t("Changed") : t("Same");

/** "Compare…" in the structure: with an earlier version, another part, or the released one. */
export function CompareButton({ obj }: { obj: VersionedObject }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button className="btn small" onClick={() => setOpen(true)}>
        <GitCompareArrows size={13} aria-hidden /> {t("Compare…")}
      </button>
      {open && <CompareDialog obj={obj} onClose={() => setOpen(false)} />}
    </>
  );
}

function CompareDialog({ obj, onClose }: { obj: VersionedObject; onClose: () => void }) {
  const navigate = useNavigate();
  const { data: history } = useHistory(obj.id);
  const [mode, setMode] = useState<"iteration" | "released" | "part">("iteration");
  const [iteration, setIteration] = useState("");
  const [text, setText] = useState("");
  const { data: types } = useTypes();
  const search = useObjectSearch(mode === "part" ? text : "", 8);
  const parts = (search.data?.pages.flat() ?? []).filter(
    (o) => types?.find((ty) => ty.id === o.typeId)?.baseClass === "PART" && o.id !== obj.id,
  );
  const earlier = (history ?? []).filter((i) => i.id !== obj.latest.id);
  /** The older (or other) structure goes on the left, so "added" means new on the right. */
  const go = (query: string) => {
    navigate(`/structure-compare?${query}`);
    onClose();
  };
  return (
    <Dialog
      open
      title={t("Compare {number} with…", { number: obj.number })}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            {t("Cancel")}
          </button>
          {mode !== "part" && (
            <button
              className="btn primary"
              disabled={mode === "iteration" && !iteration}
              onClick={() =>
                mode === "iteration"
                  ? go(`left=${obj.id}&leftIteration=${iteration}&right=${obj.id}`)
                  : go(`left=${obj.id}&leftSpec=STATE&leftState=RELEASED&right=${obj.id}`)
              }
            >
              {t("Compare")}
            </button>
          )}
        </>
      }
    >
      <fieldset className="radio-list">
        <label>
          <input type="radio" checked={mode === "iteration"} onChange={() => setMode("iteration")} />
          <span>{t("An earlier version of this part (against the latest)")}</span>
        </label>
        {mode === "iteration" && (
          <select aria-label={t("Earlier version")} value={iteration} onChange={(e) => setIteration(e.target.value)}>
            <option value="">{t("Choose a version…")}</option>
            {earlier.map((i) => (
              <option key={i.id} value={i.id}>
                {i.version}
                {i.comment ? ` · ${i.comment}` : ""}
              </option>
            ))}
          </select>
        )}
        <label>
          <input type="radio" checked={mode === "released"} onChange={() => setMode("released")} />
          <span>{t("Its latest released structure (against the latest)")}</span>
        </label>
        <label>
          <input type="radio" checked={mode === "part"} onChange={() => setMode("part")} />
          <span>{t("Another part")}</span>
        </label>
        {mode === "part" && (
          <div className="add-line-pick">
            <input
              aria-label={t("Part to compare with")}
              placeholder={t("Find a part by number or name")}
              value={text}
              onChange={(e) => setText(e.target.value)}
            />
            {text.trim() && (
              <ul className="add-line-results" role="listbox" aria-label={t("Matching parts")}>
                {parts.map((o) => (
                  <li key={o.id} role="option" aria-selected={false} onClick={() => go(`left=${obj.id}&right=${o.id}`)}>
                    <TypeIcon typeId={o.typeId} /> <strong>{o.number}</strong> {o.name}
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
      </fieldset>
    </Dialog>
  );
}

function Side({ line, other, row, unitLabel }: { line?: Line | null; other?: Line | null; row: Row; unitLabel: (k: string) => string }) {
  if (!line)
    return (
      <td colSpan={4} className="muted center">
        —
      </td>
    );
  const diff = (k: string) => (row.differences.includes(k) && other ? "diff" : undefined);
  return (
    <>
      <td className={`version ${diff("VERSION") ?? ""}`}>{line.version ?? "—"}</td>
      <td className={`num ${diff("QUANTITY") ?? ""}`}>{qty(line.quantity)}</td>
      <td className={diff("UNIT")}>{unitLabel(line.unit)}</td>
      <td className={`designators ${diff("DESIGNATORS") ?? diff("FIND_NUMBER") ?? ""}`}>
        {[line.findNumber, formatDesignators(line.designators)].filter(Boolean).join(" · ")}
      </td>
    </>
  );
}

/** Side-by-side differences between two structures. */
export function StructureComparePage() {
  const [params] = useSearchParams();
  const left = params.get("left") ?? "";
  const right = params.get("right") ?? "";
  const [onlyDifferences, setOnlyDifferences] = useState(true);
  const units = useUnits();
  const unitLabel = (key: string) => units.find((u) => u.key === key)?.label ?? key;
  const { data: leftObj } = useObject(left);
  const { data: rightObj } = useObject(right);
  const query = Object.fromEntries([...params.entries()]);
  const { data, error, isLoading } = useQuery({
    queryKey: ["structureCompare", query],
    queryFn: () => unwrap(api.GET("/api/v1/structure-compare", { params: { query: query as never } })),
    enabled: !!left && !!right,
  });
  const rows = (data?.rows ?? []).filter(
    (r) => !onlyDifferences || r.status !== "SAME" || data?.rows.some((x) => x.status !== "SAME" && x.path.startsWith(`${r.path}/`)),
  );
  const label = (o: VersionedObject | undefined, version?: string | null) => (o ? `${o.number} ${version ?? ""}`.trim() : "…");

  const exportRows = async () => {
    if (!data) return;
    const res = await api.POST("/api/v1/export/xlsx", {
      body: {
        sheetName: t("Compare"),
        columns: [
          t("Level"),
          t("Number"),
          t("Name"),
          t("Status"),
          t("Left version"),
          t("Left quantity"),
          t("Right version"),
          t("Right quantity"),
        ],
        rows: data.rows.map((r) => [
          r.level,
          r.part.number ?? "",
          r.part.name ?? "",
          STATUS(r.status),
          r.left?.version ?? "",
          r.left ? r.left.quantity : "",
          r.right?.version ?? "",
          r.right ? r.right.quantity : "",
        ]),
      },
      parseAs: "blob",
      headers: { Accept: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" },
    });
    if (!res.data) return;
    const url = URL.createObjectURL(res.data as Blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `compare.xlsx`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 10_000);
  };

  return (
    <>
      <PageHeader
        icon={<GitCompareArrows size={18} className="icon-muted" aria-hidden />}
        title={
          <span className="nm">
            {t("Compare structures")}: {label(leftObj, data?.leftVersion)} ↔ {label(rightObj, data?.rightVersion)}
          </span>
        }
        meta={
          data && (
            <span className="row" style={{ gap: 6 }}>
              <span className="tag allow">{t("{n} added", { n: data.added })}</span>
              <span className="tag deny">{t("{n} removed", { n: data.removed })}</span>
              <span className="tag warn">{t("{n} changed", { n: data.changed })}</span>
            </span>
          )
        }
        toolbar={
          <>
            <label className="row" style={{ gap: 6 }}>
              <input type="checkbox" checked={onlyDifferences} onChange={(e) => setOnlyDifferences(e.target.checked)} />{" "}
              {t("Only differences")}
            </label>
            <button className="btn ghost" onClick={() => void exportRows()} disabled={!data}>
              <FileSpreadsheet size={15} aria-hidden /> {t("Export to Excel")}
            </button>
          </>
        }
      />
      <div className="content">
        <ErrorBanner error={error} />
        {isLoading ? (
          <Loading />
        ) : (
          <Panel title={t("Differences")} flush>
            <div className="grid-wrap">
              <table className="grid structure compare" aria-label={t("Differences")}>
                <thead>
                  <tr>
                    <th rowSpan={2}>{t("Number")}</th>
                    <th rowSpan={2}>{t("Name")}</th>
                    <th rowSpan={2}>{t("Status")}</th>
                    <th colSpan={4} className="side">
                      {label(leftObj, data?.leftVersion)}
                    </th>
                    <th colSpan={4} className="side">
                      {label(rightObj, data?.rightVersion)}
                    </th>
                  </tr>
                  <tr>
                    {[0, 1].map((i) => (
                      <SideHeader key={i} />
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r) => (
                    <tr key={r.path} className={`cmp-${r.status.toLowerCase()}`}>
                      <td className="nowrap" style={{ paddingLeft: 8 + (r.level - 1) * 18 }}>
                        {r.part.typeId && <TypeIcon typeId={r.part.typeId} />}{" "}
                        {r.part.readable ? (
                          <Link className="num-link" to={`/objects/${r.part.id}?tab=structure`}>
                            {r.part.number}
                          </Link>
                        ) : (
                          t("(no access)")
                        )}
                      </td>
                      <td>{r.part.name}</td>
                      <td>
                        <span className={`tag cmp-tag-${r.status.toLowerCase()}`}>{STATUS(r.status)}</span>
                      </td>
                      <Side line={r.left} other={r.right} row={r} unitLabel={unitLabel} />
                      <Side line={r.right} other={r.left} row={r} unitLabel={unitLabel} />
                    </tr>
                  ))}
                  {rows.length === 0 && (
                    <tr>
                      <td colSpan={11} className="empty">
                        {t("The two structures are the same.")}
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </Panel>
        )}
      </div>
    </>
  );
}

function SideHeader() {
  return (
    <>
      <th>{t("Version")}</th>
      <th className="num">{t("Quantity")}</th>
      <th>{t("Unit")}</th>
      <th>{t("Find no. · ref. des.")}</th>
    </>
  );
}
