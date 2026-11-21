import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { Bookmark, Search, Share2, Trash2, X } from "lucide-react";
import { Fragment, useState, type FormEvent, type ReactNode } from "react";
import { Link, useSearchParams } from "react-router";
import { api, unwrap, type Schemas } from "../api/client";
import { useContextById, useLifecycles, useMe, useTypes } from "../api/queries";
import { pickLabel } from "../components/AttributeForm";
import {
  Dialog,
  ErrorBanner,
  Loading,
  Panel,
  PrincipalName,
  StateBadge,
  TypeIcon,
  formatDate,
  stateLabel,
  typeLabel,
} from "../components/common";
import { PageHeader } from "../components/Layout";
import { t } from "../i18n/i18n";

type Query = Schemas["SearchQuery"];
type SavedSearch = Schemas["SavedSearch"];
const PAGE = 25;

/** The search in the address, so results can be bookmarked and shared: q, type, state, context, a (name:value), sort. */
function fromParams(p: URLSearchParams): Query {
  const attributes: Record<string, string> = {};
  for (const a of p.getAll("a")) {
    const i = a.indexOf(":");
    if (i > 0) attributes[a.slice(0, i)] = a.slice(i + 1);
  }
  return {
    text: p.get("q") ?? "",
    typeIds: p.getAll("type"),
    states: p.getAll("state"),
    contextIds: p.getAll("context"),
    attributes,
    sort: p.get("sort") ?? undefined,
    offset: Number(p.get("from") ?? 0),
    limit: PAGE,
  };
}

function toParams(q: Query): URLSearchParams {
  const p = new URLSearchParams();
  if (q.text) p.set("q", q.text);
  q.typeIds?.forEach((t) => p.append("type", t));
  q.states?.forEach((s) => p.append("state", s));
  q.contextIds?.forEach((c) => p.append("context", c));
  Object.entries(q.attributes ?? {}).forEach(([k, v]) => p.append("a", `${k}:${v}`));
  if (q.sort) p.set("sort", q.sort);
  if (q.offset) p.set("from", String(q.offset));
  return p;
}

/** «found» → highlighted. */
function Snippet({ text }: { text: string }) {
  const parts = text.split(/«|»/);
  return <span className="snippet">{parts.map((s, i) => (i % 2 ? <mark key={i}>{s}</mark> : <Fragment key={i}>{s}</Fragment>))}</span>;
}

/**
 * Search (roadmap 3.6): words anywhere (numbers, names, values, files and what's in them), narrowed
 * by type, state, context and attribute values; saved and shared searches. Only what you may read.
 */
export function SearchPage() {
  const [params, setParams] = useSearchParams();
  const query = fromParams(params);
  const [text, setText] = useState(query.text ?? "");
  const [saving, setSaving] = useState(false);
  const { data: types } = useTypes();
  const { data: lifecycles } = useLifecycles();
  const { data, error, isFetching } = useQuery({
    queryKey: ["search", params.toString()],
    queryFn: () => unwrap(api.POST("/api/v1/search", { body: query })),
    placeholderData: (prev) => prev,
  });
  const set = (q: Partial<Query>) => setParams(toParams({ ...query, offset: 0, ...q }));
  const toggle = (key: "typeIds" | "states" | "contextIds", value: string) => {
    const list = query[key] ?? [];
    set({ [key]: list.includes(value) ? list.filter((v) => v !== value) : [...list, value] });
  };
  const stateName = (key: string) => {
    for (const lc of lifecycles ?? []) {
      const s = lc.states.find((x) => x.key === key);
      if (s) return pickLabel(s.labels, key);
    }
    return stateLabel(key);
  };
  const submit = (e: FormEvent) => {
    e.preventDefault();
    set({ text });
  };
  const filters =
    (query.typeIds?.length ?? 0) +
    (query.states?.length ?? 0) +
    (query.contextIds?.length ?? 0) +
    Object.keys(query.attributes ?? {}).length;

  return (
    <>
      <PageHeader
        icon={<Search size={18} className="icon-muted" aria-hidden />}
        title={<span className="nm">{t("Search")}</span>}
        meta={<span className="sub">{t("Numbers, names, values, file names and what's in the files, in every context you can read")}</span>}
      />
      <div className="content search-layout">
        <aside className="search-side">
          <SavedSearches onOpen={(q) => setParams(toParams(q))} />
          <Facet
            title={t("Type")}
            values={data?.facets.type}
            active={query.typeIds ?? []}
            label={(k) => typeLabel(types?.find((x) => x.id === k))}
            onToggle={(k) => toggle("typeIds", k)}
          />
          <Facet
            title={t("State")}
            values={data?.facets.state}
            active={query.states ?? []}
            label={stateName}
            onToggle={(k) => toggle("states", k)}
          />
          <Facet
            title={t("Context")}
            values={data?.facets.context}
            active={query.contextIds ?? []}
            label={(k) => <ContextLabel id={k} />}
            onToggle={(k) => toggle("contextIds", k)}
          />
          <AttributeFilters
            typeIds={query.typeIds?.length ? query.typeIds : BASE_TYPES}
            value={query.attributes ?? {}}
            onChange={(attributes) => set({ attributes })}
          />
        </aside>
        <div style={{ display: "grid", gap: 12, alignContent: "start", minWidth: 0 }}>
          <form className="search-box" onSubmit={submit} role="search">
            <Search size={16} className="icon-muted" aria-hidden />
            <input
              aria-label={t("Search for")}
              value={text}
              onChange={(e) => setText(e.target.value)}
              placeholder={t("Words, numbers, {q}exact phrase{q}, -excluded", { q: '"' })}
              autoFocus
            />
            <button className="btn primary" type="submit">
              {t("Search")}
            </button>
            <select
              className="filter"
              aria-label={t("Sort")}
              value={query.sort ?? ""}
              onChange={(e) => set({ sort: e.target.value || undefined })}
            >
              <option value="">{t("Best match")}</option>
              <option value="MODIFIED">{t("Newest first")}</option>
              <option value="NUMBER">{t("By number")}</option>
            </select>
            <button className="btn" type="button" onClick={() => setSaving(true)} disabled={!query.text && filters === 0}>
              <Bookmark size={14} aria-hidden /> {t("Save")}
            </button>
          </form>
          {filters > 0 && (
            <div className="row wrap" style={{ gap: 6 }}>
              {query.typeIds?.map((id) => (
                <Chip key={id} onRemove={() => toggle("typeIds", id)}>
                  {typeLabel(types?.find((x) => x.id === id))}
                </Chip>
              ))}
              {query.states?.map((s) => (
                <Chip key={s} onRemove={() => toggle("states", s)}>
                  {stateName(s)}
                </Chip>
              ))}
              {query.contextIds?.map((c) => (
                <Chip key={c} onRemove={() => toggle("contextIds", c)}>
                  <ContextLabel id={c} />
                </Chip>
              ))}
              {Object.entries(query.attributes ?? {}).map(([k, v]) => (
                <Chip
                  key={k}
                  onRemove={() => {
                    const next = { ...query.attributes };
                    delete next[k];
                    set({ attributes: next });
                  }}
                >
                  {t("{name} contains “{value}”", { name: k, value: v })}
                </Chip>
              ))}
              <button
                className="link-btn small"
                onClick={() =>
                  setParams(toParams({ text: query.text, typeIds: [], states: [], contextIds: [], attributes: {}, offset: 0, limit: PAGE }))
                }
              >
                {t("Clear filters")}
              </button>
            </div>
          )}
          <Panel
            title={t("Results")}
            note={
              data
                ? `${t("{count} found", { count: `${data.total}${data.capped ? "+" : ""}` })}${isFetching ? ` · ${t("updating")}` : ""}`
                : undefined
            }
            flush
          >
            <ErrorBanner error={error} />
            {!data ? (
              <Loading />
            ) : data.hits.length === 0 ? (
              <p className="empty">{t("Nothing found. Try fewer words, or fewer filters.")}</p>
            ) : (
              <ul className="find-results" aria-label={t("Search results")}>
                {data.hits.map((h) => (
                  <li key={h.id}>
                    <TypeIcon typeId={h.typeId} />
                    <div style={{ minWidth: 0 }}>
                      <div className="row wrap" style={{ gap: 8 }}>
                        <Link to={`/objects/${h.id}`} className="num-link">
                          {h.number}
                        </Link>
                        <Link to={`/objects/${h.id}`} className="name-link">
                          {h.name}
                        </Link>
                        <span className="tag accent version">{h.version}</span>
                        <StateBadge state={h.state} label={stateName(h.state)} />
                      </div>
                      {h.snippet && <Snippet text={h.snippet} />}
                      <div className="muted small">
                        {typeLabel(types?.find((x) => x.id === h.typeId))} · <ContextLabel id={h.contextId} /> · {formatDate(h.modifiedAt)}
                      </div>
                    </div>
                  </li>
                ))}
              </ul>
            )}
            {data && data.total > PAGE && (
              <div className="panel-foot">
                <button
                  className="btn small"
                  disabled={!query.offset}
                  onClick={() => set({ offset: Math.max(0, (query.offset ?? 0) - PAGE) })}
                >
                  {t("Previous")}
                </button>
                <span className="muted small">
                  {t("{from}–{to} of {total}", {
                    from: (query.offset ?? 0) + 1,
                    to: Math.min(data.total, (query.offset ?? 0) + PAGE),
                    total: data.total,
                  })}
                </span>
                <button
                  className="btn small"
                  disabled={(query.offset ?? 0) + PAGE >= data.total}
                  onClick={() => set({ offset: (query.offset ?? 0) + PAGE })}
                >
                  {t("Next")}
                </button>
              </div>
            )}
          </Panel>
        </div>
      </div>
      {saving && <SaveSearchDialog query={query} onClose={() => setSaving(false)} />}
    </>
  );
}

function Chip({ children, onRemove }: { children: ReactNode; onRemove: () => void }) {
  return (
    <span className="tag chip">
      {children}
      <button className="tag-remove" aria-label={t("Remove filter")} onClick={onRemove}>
        <X size={11} />
      </button>
    </span>
  );
}

function ContextLabel({ id }: { id: string }) {
  const { data } = useContextById(id);
  return <>{data?.name ?? "…"}</>;
}

function Facet({
  title,
  values,
  active,
  label,
  onToggle,
}: {
  title: string;
  values?: { key: string; count: number }[];
  active: string[];
  label: (key: string) => ReactNode;
  onToggle: (key: string) => void;
}) {
  const shown = [...(values ?? [])];
  for (const a of active) if (!shown.some((v) => v.key === a)) shown.push({ key: a, count: 0 });
  if (!shown.length) return null;
  return (
    <section className="facet" aria-label={title}>
      <h3>{title}</h3>
      {shown.slice(0, 12).map((v) => (
        <label key={v.key} className="check">
          <input type="checkbox" checked={active.includes(v.key)} onChange={() => onToggle(v.key)} />
          <span className="grow">{label(v.key)}</span>
          <span className="muted small">{v.count}</span>
        </label>
      ))}
    </section>
  );
}

/** Part and Document: their attributes are offered when no type is chosen. */
const BASE_TYPES = ["00000000-0000-7000-8000-000000000102", "00000000-0000-7000-8000-000000000101"];

function AttributeFilters({
  typeIds,
  value,
  onChange,
}: {
  typeIds: string[];
  value: Record<string, string>;
  onChange: (v: Record<string, string>) => void;
}) {
  const [name, setName] = useState("");
  const [text, setText] = useState("");
  const results = useQueries({
    queries: typeIds.slice(0, 5).map((id) => ({
      queryKey: ["typeAttributes", id],
      queryFn: () => unwrap(api.GET("/api/v1/types/{typeId}/attributes", { params: { path: { typeId: id } } })),
    })),
  });
  const attributes = [...new Map(results.flatMap((r) => r.data ?? []).map((a) => [a.name, a])).values()].sort((a, b) =>
    pickLabel(a.labels, a.name).localeCompare(pickLabel(b.labels, b.name)),
  );
  return (
    <section className="facet" aria-label={t("Attribute")}>
      <h3>{t("Attribute value")}</h3>
      <select aria-label={t("Attribute name")} value={name} onChange={(e) => setName(e.target.value)}>
        <option value="">{t("Choose an attribute…")}</option>
        {attributes.map((a) => (
          <option key={a.name} value={a.name}>
            {pickLabel(a.labels, a.name)}
          </option>
        ))}
      </select>
      <div className="row" style={{ gap: 4 }}>
        <input aria-label={t("Attribute value")} value={text} onChange={(e) => setText(e.target.value)} placeholder={t("contains…")} />
        <button
          className="btn small"
          disabled={!name || !text.trim()}
          onClick={() => {
            onChange({ ...value, [name]: text.trim() });
            setText("");
          }}
        >
          {t("Add")}
        </button>
      </div>
    </section>
  );
}

function SavedSearches({ onOpen }: { onOpen: (q: Query) => void }) {
  const qc = useQueryClient();
  const { data: me } = useMe();
  const { data: saved } = useQuery({ queryKey: ["savedSearches"], queryFn: () => unwrap(api.GET("/api/v1/saved-searches")) });
  const remove = useMutation({
    mutationFn: (id: string) => unwrap(api.DELETE("/api/v1/saved-searches/{id}", { params: { path: { id } } })),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["savedSearches"] }),
  });
  if (!saved?.length) return null;
  const row = (s: SavedSearch) => (
    <div key={s.id} className="saved-row">
      <button className="link-btn" onClick={() => onOpen(s.query)}>
        {s.name}
      </button>
      {s.shared && <Share2 size={11} className="icon-muted" aria-label={t("Shared")} />}
      {s.owner === me?.user.id ? (
        <button className="icon-btn" aria-label={t("Delete {name}", { name: s.name })} onClick={() => remove.mutate(s.id)}>
          <Trash2 size={12} />
        </button>
      ) : (
        <span className="muted small">
          <PrincipalName id={s.owner} />
        </span>
      )}
    </div>
  );
  const mine = saved.filter((s) => s.owner === me?.user.id);
  const others = saved.filter((s) => s.owner !== me?.user.id);
  return (
    <section className="facet" aria-label={t("Saved searches")}>
      <h3>{t("Saved searches")}</h3>
      {mine.map(row)}
      {others.length > 0 && (
        <div className="muted small" style={{ marginTop: 6 }}>
          {t("Shared with you")}
        </div>
      )}
      {others.map(row)}
    </section>
  );
}

function SaveSearchDialog({ query, onClose }: { query: Query; onClose: () => void }) {
  const qc = useQueryClient();
  const [name, setName] = useState(query.text ? `“${query.text}”` : "");
  const [shared, setShared] = useState(false);
  const save = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/saved-searches", { body: { name, query, shared } })),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["savedSearches"] });
      onClose();
    },
  });
  return (
    <Dialog
      open
      title={t("Save this search")}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            {t("Cancel")}
          </button>
          <button className="btn primary" disabled={save.isPending || !name.trim()} onClick={() => save.mutate()}>
            {t("Save")}
          </button>
        </>
      }
    >
      <div className="attr-form">
        <ErrorBanner error={save.error} />
        <div className="field wide">
          <label htmlFor="saved-name">{t("Name")}</label>
          <input id="saved-name" value={name} onChange={(e) => setName(e.target.value)} autoFocus />
        </div>
        <label className="check wide">
          <input type="checkbox" checked={shared} onChange={(e) => setShared(e.target.checked)} />
          {t("Share it with everyone (each person still finds only what they may read)")}
        </label>
      </div>
    </Dialog>
  );
}
