import { StandInPanel } from "../workflow/StandIn";
import { AccessTokensPanel } from "../personal/AccessTokens";
import { Lock, RotateCcw, SlidersHorizontal } from "lucide-react";
import { useState } from "react";
import { Link } from "react-router";
import {
  useContextById,
  useContextPreferences,
  useMyPreferences,
  useSetPreference,
  type ContextPreference,
  type UserPreference,
} from "../api/queries";
import { EmptyState, ErrorBanner, Loading, Panel } from "../components/common";
import { PageHeader } from "../components/Layout";
import { t } from "../i18n/i18n";

type Pref = ContextPreference | UserPreference;

function show(p: Pref, value: unknown): string {
  if (value === null || value === undefined) return p.definition.type === "CHOICE" ? t("None") : "—";
  if (typeof value === "boolean") return value ? t("Yes") : t("No");
  if (p.definition.type === "CHOICE") return p.definition.options.find((o) => o.value === value)?.label ?? String(value);
  return String(value);
}

function ContextName({ id }: { id: string }) {
  const { data } = useContextById(id);
  return data ? <Link to={`/contexts/${id}?tab=preferences`}>{data.name}</Link> : <span className="muted">…</span>;
}

/** Where the value in effect comes from, in words. */
function Source({ p, here }: { p: Pref; here?: string }) {
  const e = p.effective;
  if (e.lockedAt && e.lockedAt !== here) {
    return (
      <span className="pref-source locked">
        <Lock size={11} aria-hidden /> {t("Locked by")} <ContextName id={e.lockedAt} />
      </span>
    );
  }
  if (e.source === "USER") return <span className="pref-source">{t("Your setting")}</span>;
  if (e.source === "CONTEXT" && e.sourceContextId) {
    return e.sourceContextId === here ? (
      <span className="pref-source own">{e.lockedAt ? t("Set here, locked") : t("Set here")}</span>
    ) : (
      <span className="pref-source">
        {t("From")} <ContextName id={e.sourceContextId} />
      </span>
    );
  }
  return <span className="pref-source">{t("Default")}</span>;
}

function ValueInput({ p, id, value, onChange }: { p: Pref; id: string; value: unknown; onChange: (v: unknown) => void }) {
  const d = p.definition;
  switch (d.type) {
    case "BOOLEAN":
      return (
        <select id={id} value={value === true ? "true" : "false"} onChange={(e) => onChange(e.target.value === "true")}>
          <option value="true">{t("Yes")}</option>
          <option value="false">{t("No")}</option>
        </select>
      );
    case "INTEGER":
      return (
        <input
          id={id}
          type="number"
          min={d.min ?? undefined}
          max={d.max ?? undefined}
          value={value === undefined || value === null ? "" : String(value)}
          onChange={(e) => onChange(e.target.value === "" ? undefined : Number(e.target.value))}
        />
      );
    case "CHOICE":
      return (
        <select id={id} value={(value as string | undefined) ?? ""} onChange={(e) => onChange(e.target.value || undefined)}>
          <option value="">{t("Choose…")}</option>
          {d.options.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </select>
      );
    default:
      return <input id={id} value={(value as string | undefined) ?? ""} onChange={(e) => onChange(e.target.value)} />;
  }
}

function PreferenceRow({ p, contextId }: { p: Pref; contextId?: string }) {
  const set = useSetPreference(contextId ? { contextId } : "me");
  const own = p.own;
  const [value, setValue] = useState<unknown>(own?.value ?? p.effective.value);
  const [locked, setLocked] = useState(own?.locked ?? false);
  const id = `pref-${p.definition.key}`;
  const dirty = own ? value !== own.value || locked !== own.locked : value !== p.effective.value || locked;

  return (
    <li className="pref-item" aria-label={p.definition.label}>
      <div className="pref-info">
        <label htmlFor={id} className="pref-label">
          {p.definition.label}
        </label>
        {p.definition.description && <p className="pref-desc">{p.definition.description}</p>}
        <div className="pref-effect">
          <span>
            {t("In effect")}: <strong>{show(p, p.effective.value)}</strong>
          </span>
          <Source p={p} here={contextId} />
        </div>
        <ErrorBanner error={set.error} />
      </div>
      <div className="pref-control">
        {p.canSet ? (
          <>
            <ValueInput p={p} id={id} value={value} onChange={setValue} />
            <div className="pref-actions">
              {contextId && (
                <label className="check" title={t("Nothing below this context can change it")}>
                  <input type="checkbox" checked={locked} onChange={(e) => setLocked(e.target.checked)} />
                  {t("Lock")}
                </label>
              )}
              <span className="grow" />
              {own && !dirty && (
                <button
                  className="btn small ghost"
                  aria-label={t("Reset {preference}", { preference: p.definition.label })}
                  title={contextId ? t("Remove the value set here: inherit again") : t("Back to the default")}
                  onClick={() => set.mutate({ key: p.definition.key, clear: true })}
                >
                  <RotateCcw size={13} aria-hidden /> {t("Reset")}
                </button>
              )}
              {dirty && (
                <>
                  <button
                    className="btn small ghost"
                    onClick={() => (setValue(own?.value ?? p.effective.value), setLocked(own?.locked ?? false))}
                  >
                    {t("Cancel")}
                  </button>
                  <button
                    className="btn small primary"
                    disabled={value === undefined || set.isPending}
                    onClick={() => set.mutate({ key: p.definition.key, value, locked })}
                  >
                    {t("Save")}
                  </button>
                </>
              )}
            </div>
          </>
        ) : (
          <span className="pref-locked">
            <Lock size={13} aria-hidden /> {p.effective.lockedAt ? t("Locked above") : t("You can't change this here")}
          </span>
        )}
      </div>
    </li>
  );
}

function PreferenceTable({ prefs, contextId }: { prefs: Pref[]; contextId?: string }) {
  const categories = [...new Set(prefs.map((p) => p.definition.category))];
  if (prefs.length === 0) return <EmptyState icon={<SlidersHorizontal size={18} />} title={t("No preferences can be set here.")} />;
  return (
    <div className="stack">
      {categories.map((c) => (
        <Panel key={c} title={c} flush className="pref-panel">
          <ul className="pref-list" aria-label={t("{category} preferences", { category: c })}>
            {prefs
              .filter((p) => p.definition.category === c)
              .map((p) => (
                // Remount after a save, so the editor starts from the stored value.
                <PreferenceRow key={`${p.definition.key}-${p.own?.setAt ?? "none"}`} p={p} contextId={contextId} />
              ))}
          </ul>
        </Panel>
      ))}
    </div>
  );
}

/** A context's Preferences tab: values set here apply to everything below, unless something above locked them. */
export function PreferencesTab({ contextId }: { contextId: string }) {
  const { data, isLoading, error } = useContextPreferences(contextId);
  if (isLoading) return <Loading />;
  return (
    <>
      <ErrorBanner error={error} />
      <PreferenceTable prefs={data ?? []} contextId={contextId} />
    </>
  );
}

/** The user's own preferences. */
export function MyPreferencesPage() {
  const { data, isLoading, error } = useMyPreferences();
  return (
    <>
      <PageHeader
        icon={<SlidersHorizontal size={18} className="icon-muted" aria-hidden />}
        title={<span className="nm">{t("My preferences")}</span>}
        meta={
          <span className="sub">
            {t("Apply wherever an administrator hasn't locked a value. Mail about notifications is set under")}{" "}
            <Link to="/notifications">{t("Notifications")}</Link>.
          </span>
        }
      />
      <div className="content">
        <ErrorBanner error={error} />
        <StandInPanel />
        <AccessTokensPanel />
        {isLoading ? <Loading /> : <PreferenceTable prefs={data ?? []} />}
      </div>
    </>
  );
}
