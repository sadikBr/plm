import { useQuery } from "@tanstack/react-query";
import { FolderTree, Search } from "lucide-react";
import { useMemo, useState } from "react";
import { Link, useSearchParams } from "react-router";
import { api, unwrap, type Schemas } from "../api/client";
import type { VersionedObject } from "../api/queries";
import { ErrorBanner, Loading, Panel, StateBadge } from "../components/common";
import { t } from "../i18n/i18n";
import { flatten, inherited, useClassTree } from "./Classification";
import { PageHeader } from "../components/Layout";

type Attr = Schemas["ClassAttribute"];
type Filter = Schemas["ClassFilter"];

const shown = (a: Attr, v: unknown) =>
  v == null ? (
    <span className="muted">—</span>
  ) : a.dataType === "BOOLEAN" ? (
    v ? (
      t("Yes")
    ) : (
      t("No")
    )
  ) : (
    `${String(v)}${a.unit ? ` ${a.unit}` : ""}`
  );

/** The filter fields of one attribute: a range for numbers, a value for the rest. */
function FilterField({ a, value, onChange }: { a: Attr; value: Filter | undefined; onChange: (f: Filter | undefined) => void }) {
  const set = (patch: Partial<Filter>) => {
    const next = { key: a.key, ...value, ...patch };
    const empty = next.min == null && next.max == null && !next.value;
    onChange(empty ? undefined : next);
  };
  const num = (s: string) => (s.trim() === "" || Number.isNaN(Number(s)) ? undefined : Number(s));
  return (
    <label className="field class-filter">
      <span>
        {a.label} {a.unit && <span className="muted">({a.unit})</span>}
      </span>
      {a.dataType === "NUMBER" ? (
        <span className="row">
          <input
            aria-label={t("{label} from", { label: a.label })}
            placeholder={t("min")}
            defaultValue={value?.min ?? ""}
            onChange={(e) => set({ min: num(e.target.value) })}
          />
          <span className="muted">–</span>
          <input
            aria-label={t("{label} to", { label: a.label })}
            placeholder={t("max")}
            defaultValue={value?.max ?? ""}
            onChange={(e) => set({ max: num(e.target.value) })}
          />
        </span>
      ) : a.dataType === "TEXT" ? (
        <input
          aria-label={a.label}
          placeholder={t("contains")}
          defaultValue={value?.value ?? ""}
          onChange={(e) => set({ value: e.target.value || undefined })}
        />
      ) : (
        <select aria-label={a.label} value={value?.value ?? ""} onChange={(e) => set({ value: e.target.value || undefined })}>
          <option value="">{t("Any")}</option>
          {(a.dataType === "ENUM" ? a.allowed : ["true", "false"]).map((v) => (
            <option key={v} value={v}>
              {a.dataType === "BOOLEAN" ? (v === "true" ? t("Yes") : t("No")) : v}
            </option>
          ))}
        </select>
      )}
    </label>
  );
}

/** Classification search (roadmap 7.12): browse the class tree, filter a class's parts by its attributes. */
export function ClassSearchPage() {
  const { data: tree, error } = useClassTree();
  const [params, setParams] = useSearchParams();
  const classId = params.get("class");
  const [filters, setFilters] = useState<Record<string, Filter>>({});
  const flat = useMemo(() => flatten(tree ?? []), [tree]);
  const list = Object.values(filters);
  const { data: result, error: searchError } = useQuery({
    queryKey: ["classSearch", classId, list],
    queryFn: () =>
      unwrap(api.POST("/api/v1/classes/{id}/search", { params: { path: { id: classId! } }, body: { filters: list, limit: 200 } })),
    enabled: !!classId,
  });
  if (!tree) return error ? <ErrorBanner error={error} /> : <Loading />;
  const node = tree.find((n) => n.id === classId);
  const attrs = node ? inherited(tree, node.id) : [];
  return (
    <>
      <PageHeader
        icon={<FolderTree size={18} className="icon-muted" aria-hidden />}
        title={<span className="nm">{t("Classes")}</span>}
        meta={<span className="sub">{t("Find parts by what they are: pick a class, then narrow by its attributes.")}</span>}
      />
      <div className="content">
        <div className="class-search">
          <Panel title={t("Class tree")} flush>
            <ul className="class-tree" aria-label={t("Class tree")}>
              {flat.map(({ node: n, depth }) => (
                <li key={n.id} className={n.id === classId ? "selected" : ""} style={{ paddingLeft: 10 + depth * 18 }}>
                  <button
                    className="link-btn"
                    onClick={() => {
                      setFilters({});
                      setParams({ class: n.id });
                    }}
                  >
                    {n.name}
                  </button>
                  {n.parts > 0 && <span className="muted small">{n.parts}</span>}
                </li>
              ))}
            </ul>
          </Panel>
          <div>
            {!node ? (
              <div className="empty">{t("Pick a class to see its parts.")}</div>
            ) : (
              <>
                {attrs.length > 0 && (
                  <Panel title={t("Filter {name}", { name: node.name })} note={t("Parts of its subclasses count too.")}>
                    <div className="row wrap" key={node.id}>
                      {attrs.map((a) => (
                        <FilterField
                          key={a.id}
                          a={a}
                          value={filters[a.key]}
                          onChange={(f) => {
                            const next = { ...filters };
                            if (f) next[a.key] = f;
                            else delete next[a.key];
                            setFilters(next);
                          }}
                        />
                      ))}
                    </div>
                  </Panel>
                )}
                <ErrorBanner error={searchError} />
                <Panel
                  title={t("Parts")}
                  note={
                    result ? (result.truncated ? t("The first {n}", { n: result.parts.length }) : String(result.parts.length)) : undefined
                  }
                  flush
                >
                  {!result ? (
                    <Loading />
                  ) : result.parts.length ? (
                    <div className="grid-wrap">
                      <table className="grid" aria-label={t("Classified parts")}>
                        <thead>
                          <tr>
                            <th>{t("Number")}</th>
                            <th>{t("Name")}</th>
                            <th>{t("Version")}</th>
                            <th>{t("Class")}</th>
                            {result.attributes.map((a) => (
                              <th key={a.id} className={a.dataType === "NUMBER" ? "num" : ""}>
                                {a.label}
                              </th>
                            ))}
                          </tr>
                        </thead>
                        <tbody>
                          {result.parts.map((p) => (
                            <tr key={p.id}>
                              <td>
                                <Link to={`/objects/${p.id}?tab=classification`}>{p.number}</Link>
                              </td>
                              <td className="nowrap">{p.name}</td>
                              <td className="nowrap">
                                {p.version} <StateBadge state={p.state} />
                              </td>
                              <td className="muted small nowrap">{p.className}</td>
                              {result.attributes.map((a) => (
                                <td key={a.id} className={a.dataType === "NUMBER" ? "num nowrap" : ""}>
                                  {shown(a, p.values[a.key])}
                                </td>
                              ))}
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  ) : (
                    <div className="empty">
                      <Search size={14} aria-hidden /> {t("No parts match.")}
                    </div>
                  )}
                </Panel>
              </>
            )}
          </div>
        </div>
      </div>
    </>
  );
}

/** Parts like this one (roadmap 7.12): its class's others, nearest values first. */
export function SimilarPartsPanel({ obj }: { obj: VersionedObject }) {
  const { data, error } = useQuery({
    queryKey: ["similar", obj.id],
    queryFn: () => unwrap(api.GET("/api/v1/objects/{id}/similar", { params: { path: { id: obj.id }, query: { limit: 8 } } })),
  });
  if (error) return <ErrorBanner error={error} />;
  if (!data?.classId) return null;
  const compared = data.attributes.filter((a) => data.values[a.key] != null);
  return (
    <Panel title={t("Similar parts")} note={t("Others of its class, closest values first: reuse one before making a new part.")} flush>
      {data.similar.length ? (
        <div className="grid-wrap">
          <table className="grid similar" aria-label={t("Similar parts")}>
            <thead>
              <tr>
                <th>{t("Part")}</th>
                <th>{t("Likeness")}</th>
                {compared.map((a) => (
                  <th key={a.id} className={a.dataType === "NUMBER" ? "num" : ""}>
                    {a.label}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              <tr className="this-part">
                <td>
                  <strong>{t("This part")}</strong>
                </td>
                <td />
                {compared.map((a) => (
                  <td key={a.id} className={a.dataType === "NUMBER" ? "num nowrap" : ""}>
                    {shown(a, data.values[a.key])}
                  </td>
                ))}
              </tr>
              {data.similar.map((s) => (
                <tr key={s.part.id}>
                  <td>
                    <Link to={`/objects/${s.part.id}?tab=classification`}>{s.part.number}</Link> {s.part.name}
                  </td>
                  <td className="nowrap">
                    <span className="likeness" aria-label={t("{p}% alike", { p: Math.round(s.score * 100) })}>
                      <span style={{ width: `${Math.round(s.score * 100)}%` }} />
                    </span>{" "}
                    <span className="num-text">{Math.round(s.score * 100)}%</span>
                  </td>
                  {compared.map((a) => {
                    const m = s.matches.find((x) => x.key === a.key)?.score ?? 0;
                    return (
                      <td
                        key={a.id}
                        className={`${a.dataType === "NUMBER" ? "num nowrap" : ""} ${m === 1 ? "same" : m === 0 ? "far" : ""}`}
                      >
                        {shown(a, s.part.values[a.key])}
                      </td>
                    );
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <div className="empty small">{t("No other parts in its class yet.")}</div>
      )}
    </Panel>
  );
}
