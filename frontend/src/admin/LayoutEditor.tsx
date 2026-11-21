import { ArrowDown, ArrowUp, EyeOff, Plus, RotateCcw, Save, Trash2, X } from "lucide-react";
import { useMemo, useState } from "react";
import { useLayout, useTypeAttributes, type AttributeDefinition, type LayoutGroup } from "../api/queries";
import { AttributeForm, defaultValues, pickLabel } from "../components/AttributeForm";
import { ErrorBanner, Loading, Panel } from "../components/common";
import { language, t, tn } from "../i18n/i18n";
import { useOwnLayouts, useRemoveLayout, useSaveLayout, type LayoutScreenKey, type TypeDefinition } from "./adminQueries";

const SCREENS = (): { key: LayoutScreenKey; label: string; help: string }[] => [
  { key: "DEFAULT", label: t("Default"), help: t("Used by every screen that has no layout of its own") },
  { key: "CREATE", label: t("Create form"), help: t("The New object dialog") },
  { key: "EDIT", label: t("Edit form"), help: t("The working copy form") },
  { key: "INFO", label: t("Attributes panel"), help: t("The read-only view on the object page") },
];

interface Draft {
  groups: LayoutGroup[];
  hidden: string[];
}

function camel(label: string): string {
  const words = label
    .replace(/[^A-Za-z0-9]+/g, " ")
    .trim()
    .split(" ")
    .filter(Boolean);
  const key = words.map((w, i) => (i === 0 ? w.toLowerCase() : w[0]!.toUpperCase() + w.slice(1).toLowerCase())).join("");
  return /^[a-z]/.test(key) ? key : `group${key}`;
}

function uniqueKey(label: string, taken: Set<string>): string {
  const base = camel(label) || "group";
  let key = base === "more" ? "moreGroup" : base;
  for (let i = 2; taken.has(key); i++) key = `${base}${i}`;
  return key;
}

export function LayoutEditor({ type, typeName }: { type: TypeDefinition; typeName: (id: string) => string }) {
  const [screen, setScreen] = useState<LayoutScreenKey>("DEFAULT");
  const { data: own } = useOwnLayouts(type.id);
  const { data: resolved } = useLayout(type.id, screen);
  const { data: definitions } = useTypeAttributes(type.id);

  return (
    <div className="stack">
      <div className="segmented" role="radiogroup" aria-label={t("Screen")}>
        {SCREENS().map((s) => (
          <label key={s.key} className={screen === s.key ? "on" : undefined} title={s.help}>
            <input type="radio" name="layout-screen" checked={screen === s.key} onChange={() => setScreen(s.key)} />
            {s.label}
            {own?.some((l) => l.screen === s.key) && <span className="dot" aria-label={t("(own layout)")} />}
          </label>
        ))}
      </div>
      {own && resolved && definitions ? (
        <ScreenEditor
          // A fresh draft per type and screen.
          key={`${type.id}-${screen}`}
          type={type}
          screen={screen}
          ownLayout={own.find((l) => l.screen === screen)}
          inherited={resolved}
          definitions={definitions}
          typeName={typeName}
        />
      ) : (
        <Loading />
      )}
    </div>
  );
}

function ScreenEditor({
  type,
  screen,
  ownLayout,
  inherited,
  definitions,
  typeName,
}: {
  type: TypeDefinition;
  screen: LayoutScreenKey;
  ownLayout?: { groups: LayoutGroup[]; hidden: string[] };
  inherited: { source?: { typeId: string; screen: string } | null; groups: LayoutGroup[] };
  definitions: AttributeDefinition[];
  typeName: (id: string) => string;
}) {
  const save = useSaveLayout(type.id);
  const remove = useRemoveLayout(type.id);
  const active = useMemo(() => new Set(definitions.map((d) => d.name)), [definitions]);
  const byName = useMemo(() => new Map(definitions.map((d) => [d.name, d])), [definitions]);
  const label = (name: string) => pickLabel(byName.get(name)?.labels, name);

  // Start from this type's own layout, or a copy of what it inherits now (minus the automatic "more" group).
  const initial: Draft = ownLayout
    ? {
        groups: ownLayout.groups.map((g) => ({ ...g, attributes: g.attributes.filter((n) => active.has(n)) })),
        hidden: ownLayout.hidden.filter((n) => active.has(n)),
      }
    : { groups: inherited.groups.filter((g) => g.key !== "more"), hidden: [] };
  const [draft, setDraft] = useState<Draft>(initial);
  const [dirty, setDirty] = useState(false);
  const [newGroup, setNewGroup] = useState("");
  const change = (next: Draft) => {
    setDraft(next);
    setDirty(true);
  };

  const placed = new Set(draft.groups.flatMap((g) => g.attributes));
  const unplaced = definitions.map((d) => d.name).filter((n) => !placed.has(n) && !draft.hidden.includes(n));
  const blocksHiding = (name: string) => {
    const d = byName.get(name);
    return screen !== "INFO" && !!d?.required && d.defaultValue == null;
  };

  const updateGroup = (i: number, patch: Partial<LayoutGroup>) =>
    change({ ...draft, groups: draft.groups.map((g, j) => (j === i ? { ...g, ...patch } : g)) });
  const moveGroup = (i: number, by: number) => {
    const groups = [...draft.groups];
    const [g] = groups.splice(i, 1);
    groups.splice(i + by, 0, g!);
    change({ ...draft, groups });
  };
  const moveAttribute = (gi: number, ai: number, by: number) => {
    const attrs = [...draft.groups[gi]!.attributes];
    const [a] = attrs.splice(ai, 1);
    attrs.splice(ai + by, 0, a!);
    updateGroup(gi, { attributes: attrs });
  };
  const place = (name: string, groupKey: string) =>
    change({
      hidden: draft.hidden.filter((n) => n !== name),
      groups: draft.groups.map((g) => ({
        ...g,
        attributes: g.key === groupKey ? [...g.attributes.filter((n) => n !== name), name] : g.attributes.filter((n) => n !== name),
      })),
    });
  const unplace = (name: string) =>
    change({ ...draft, groups: draft.groups.map((g) => ({ ...g, attributes: g.attributes.filter((n) => n !== name) })) });

  const previewGroups: LayoutGroup[] = [
    ...draft.groups,
    { key: "more", labels: { en: t("More attributes") }, columns: 2, attributes: unplaced },
  ].filter((g) => g.attributes.length > 0);

  const source = inherited.source;
  const sourceScreen = source ? SCREENS().find((s) => s.key === source.screen)?.label : undefined;
  const sourceText = ownLayout
    ? t("This type has its own layout for this screen.")
    : source
      ? t("Inherited from {type} ({screen}). Saving creates this type's own layout.", {
          type: typeName(source.typeId),
          // German nouns keep their capital.
          screen: String(language() === "de" ? sourceScreen : sourceScreen?.toLowerCase()),
        })
      : t("No layout anywhere in the hierarchy: every attribute in one group. Saving creates this type's own layout.");

  return (
    <div className="cols-layout">
      <Panel
        title={t("Groups")}
        note={sourceText}
        footer={
          <>
            <button
              className="btn primary"
              disabled={save.isPending || draft.groups.length === 0 || (!dirty && !!ownLayout)}
              onClick={() =>
                save.mutate({ screen, body: { groups: draft.groups, hidden: draft.hidden } }, { onSuccess: () => setDirty(false) })
              }
            >
              <Save size={14} aria-hidden />
              {t("Save layout")}
            </button>
            {ownLayout && (
              <button className="btn" disabled={remove.isPending} onClick={() => remove.mutate(screen)}>
                <RotateCcw size={14} aria-hidden />
                {t("Use the inherited layout")}
              </button>
            )}
            {dirty && <span className="muted">{t("Unsaved changes")}</span>}
          </>
        }
      >
        <ErrorBanner error={save.error ?? remove.error} />
        <div className="layout-groups">
          {draft.groups.map((g, gi) => (
            <section key={g.key} className="layout-group" aria-label={t("Group {name}", { name: pickLabel(g.labels, g.key) })}>
              <div className="layout-group-head">
                <input
                  aria-label={t("Group label")}
                  value={g.labels.en ?? ""}
                  onChange={(e) => updateGroup(gi, { labels: { ...g.labels, en: e.target.value } })}
                />
                <select aria-label={t("Columns")} value={g.columns} onChange={(e) => updateGroup(gi, { columns: Number(e.target.value) })}>
                  <option value={2}>{tn("{n} column", "{n} columns", 2)}</option>
                  <option value={1}>{tn("{n} column", "{n} columns", 1)}</option>
                </select>
                <button
                  className="btn small ghost icon"
                  aria-label={t("Move group up")}
                  disabled={gi === 0}
                  onClick={() => moveGroup(gi, -1)}
                >
                  <ArrowUp size={13} aria-hidden />
                </button>
                <button
                  className="btn small ghost icon"
                  aria-label={t("Move group down")}
                  disabled={gi === draft.groups.length - 1}
                  onClick={() => moveGroup(gi, 1)}
                >
                  <ArrowDown size={13} aria-hidden />
                </button>
                <button
                  className="btn small ghost icon"
                  aria-label={t("Delete group")}
                  title={t("Delete the group; its attributes become unplaced")}
                  onClick={() => change({ ...draft, groups: draft.groups.filter((_, j) => j !== gi) })}
                >
                  <Trash2 size={13} aria-hidden />
                </button>
              </div>
              {g.attributes.length === 0 && <p className="muted small-note">{t("Empty: add attributes from the list below.")}</p>}
              <ul className="layout-attrs">
                {g.attributes.map((name, ai) => (
                  <li key={name}>
                    <span className="grow">
                      {label(name)} <code className="muted">{name}</code>
                    </span>
                    <button
                      className="btn small ghost icon"
                      aria-label={t("Move {value} up", { value: label(name) })}
                      disabled={ai === 0}
                      onClick={() => moveAttribute(gi, ai, -1)}
                    >
                      <ArrowUp size={12} aria-hidden />
                    </button>
                    <button
                      className="btn small ghost icon"
                      aria-label={t("Move {value} down", { value: label(name) })}
                      disabled={ai === g.attributes.length - 1}
                      onClick={() => moveAttribute(gi, ai, 1)}
                    >
                      <ArrowDown size={12} aria-hidden />
                    </button>
                    {draft.groups.length > 1 && (
                      <select
                        aria-label={t("Move {name} to group", { name: label(name) })}
                        value={g.key}
                        onChange={(e) => place(name, e.target.value)}
                      >
                        {draft.groups.map((other) => (
                          <option key={other.key} value={other.key}>
                            {pickLabel(other.labels, other.key)}
                          </option>
                        ))}
                      </select>
                    )}
                    <button
                      className="btn small ghost icon"
                      aria-label={t("Take {name} out of the group", { name: label(name) })}
                      title={t("Take out of the group")}
                      onClick={() => unplace(name)}
                    >
                      <X size={12} aria-hidden />
                    </button>
                  </li>
                ))}
              </ul>
            </section>
          ))}
          <div className="row">
            <input
              aria-label={t("New group label")}
              placeholder={t("New group, e.g. Thread")}
              value={newGroup}
              onChange={(e) => setNewGroup(e.target.value)}
            />
            <button
              className="btn"
              disabled={!newGroup.trim()}
              onClick={() => {
                const key = uniqueKey(newGroup, new Set(draft.groups.map((g) => g.key)));
                change({ ...draft, groups: [...draft.groups, { key, labels: { en: newGroup.trim() }, columns: 2, attributes: [] }] });
                setNewGroup("");
              }}
            >
              <Plus size={14} aria-hidden />
              {t("Add group")}
            </button>
          </div>
        </div>

        <div className="section-label" style={{ marginTop: 14 }}>
          {t("Not placed ({count})", { count: unplaced.length })}
        </div>
        <p className="muted small-note">{t("Shown under “More attributes” after your groups, unless hidden.")}</p>
        <ul className="layout-attrs">
          {unplaced.map((name) => (
            <li key={name}>
              <span className="grow">
                {label(name)} <code className="muted">{name}</code>
              </span>
              {draft.groups.length > 0 && (
                <select
                  aria-label={t("Add {name} to group", { name: label(name) })}
                  value=""
                  onChange={(e) => e.target.value && place(name, e.target.value)}
                >
                  <option value="">{t("Add to group…")}</option>
                  {draft.groups.map((g) => (
                    <option key={g.key} value={g.key}>
                      {pickLabel(g.labels, g.key)}
                    </option>
                  ))}
                </select>
              )}
              <button
                className="btn small ghost"
                disabled={blocksHiding(name)}
                title={blocksHiding(name) ? t("Required with no default: it must stay on forms") : t("Don't show it on this screen")}
                onClick={() => change({ ...draft, hidden: [...draft.hidden, name] })}
              >
                <EyeOff size={12} aria-hidden />
                {t("Hide")}
              </button>
            </li>
          ))}
        </ul>
        {draft.hidden.length > 0 && (
          <>
            <div className="section-label" style={{ marginTop: 14 }}>
              {t("Hidden on this screen")}
            </div>
            <ul className="layout-attrs">
              {draft.hidden.map((name) => (
                <li key={name}>
                  <span className="grow muted">{label(name)}</span>
                  <button className="btn small ghost" onClick={() => change({ ...draft, hidden: draft.hidden.filter((n) => n !== name) })}>
                    {t("Show")}
                  </button>
                </li>
              ))}
            </ul>
          </>
        )}
      </Panel>
      <Panel
        title={t("Preview")}
        note={screen === "INFO" ? t("Shown as a form here; the panel shows the same groups read-only") : t("What users will see")}
      >
        <AttributeForm
          idPrefix="preview"
          definitions={definitions}
          groups={previewGroups}
          values={defaultValues(definitions)}
          onChange={() => {}}
        />
      </Panel>
    </div>
  );
}
