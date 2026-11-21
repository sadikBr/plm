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
import { ErrorBanner, Loading, Panel } from "../components/common";
import { PageHeader } from "../components/Layout";

type Pref = ContextPreference | UserPreference;

function show(p: Pref, value: unknown): string {
  if (value === null || value === undefined) return p.definition.type === "CHOICE" ? "None" : "—";
  if (typeof value === "boolean") return value ? "Yes" : "No";
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
        <Lock size={11} aria-hidden /> Locked by <ContextName id={e.lockedAt} />
      </span>
    );
  }
  if (e.source === "USER") return <span className="pref-source">Your setting</span>;
  if (e.source === "CONTEXT" && e.sourceContextId) {
    return e.sourceContextId === here ? (
      <span className="pref-source own">Set here{e.lockedAt ? ", locked" : ""}</span>
    ) : (
      <span className="pref-source">
        From <ContextName id={e.sourceContextId} />
      </span>
    );
  }
  return <span className="pref-source">Default</span>;
}

function ValueInput({ p, id, value, onChange }: { p: Pref; id: string; value: unknown; onChange: (v: unknown) => void }) {
  const d = p.definition;
  switch (d.type) {
    case "BOOLEAN":
      return (
        <select id={id} value={value === true ? "true" : "false"} onChange={(e) => onChange(e.target.value === "true")}>
          <option value="true">Yes</option>
          <option value="false">No</option>
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
          <option value="">Choose…</option>
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
  const dirty = !own || value !== own.value || locked !== own.locked;

  return (
    <tr aria-label={p.definition.label}>
      <td>
        <label htmlFor={id} className="pref-label">
          {p.definition.label}
        </label>
        <div className="muted small-note">{p.definition.description}</div>
      </td>
      <td className="nowrap">
        <strong>{show(p, p.effective.value)}</strong>
        <div>
          <Source p={p} here={contextId} />
        </div>
      </td>
      <td>
        {p.canSet ? (
          <div className="row">
            <ValueInput p={p} id={id} value={value} onChange={setValue} />
            {contextId && (
              <label className="check" title="Nothing below this context can change it">
                <input type="checkbox" checked={locked} onChange={(e) => setLocked(e.target.checked)} />
                Lock
              </label>
            )}
          </div>
        ) : (
          <span className="muted">{p.effective.lockedAt ? "Locked above" : "You can't change this here"}</span>
        )}
        <ErrorBanner error={set.error} />
      </td>
      <td className="nowrap">
        {p.canSet && (
          <>
            <button
              className="btn small primary"
              disabled={!dirty || value === undefined || set.isPending}
              onClick={() => set.mutate({ key: p.definition.key, value, locked })}
            >
              Save
            </button>{" "}
            {own && (
              <button
                className="btn small ghost icon"
                aria-label={`Reset ${p.definition.label}`}
                title={contextId ? "Remove the value set here: inherit again" : "Back to the default"}
                onClick={() => set.mutate({ key: p.definition.key, clear: true })}
              >
                <RotateCcw size={13} aria-hidden />
              </button>
            )}
          </>
        )}
      </td>
    </tr>
  );
}

function PreferenceTable({ prefs, contextId }: { prefs: Pref[]; contextId?: string }) {
  const categories = [...new Set(prefs.map((p) => p.definition.category))];
  if (prefs.length === 0) return <p className="empty">No preferences can be set here.</p>;
  return (
    <div className="stack">
      {categories.map((c) => (
        <Panel key={c} title={c} flush>
          <table className="grid prefs" aria-label={`${c} preferences`}>
            <colgroup>
              <col />
              <col style={{ width: 220 }} />
              <col style={{ width: 340 }} />
              <col style={{ width: 130 }} />
            </colgroup>
            <thead>
              <tr>
                <th>Preference</th>
                <th>In effect</th>
                <th>Set here</th>
                <th aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {prefs
                .filter((p) => p.definition.category === c)
                .map((p) => (
                  // Remount after a save, so the editor starts from the stored value.
                  <PreferenceRow key={`${p.definition.key}-${p.own?.setAt ?? "none"}`} p={p} contextId={contextId} />
                ))}
            </tbody>
          </table>
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
        title={<span className="nm">My preferences</span>}
        meta={
          <span className="sub">
            Apply wherever an administrator hasn't locked a value. Mail about notifications is set under{" "}
            <Link to="/notifications">Notifications</Link>.
          </span>
        }
      />
      <div className="content">
        <ErrorBanner error={error} />
        {isLoading ? <Loading /> : <PreferenceTable prefs={data ?? []} />}
      </div>
    </>
  );
}
