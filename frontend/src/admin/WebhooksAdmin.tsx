import { Pencil, Plus, RotateCcw, Trash2 } from "lucide-react";
import { useState } from "react";
import { Dialog, ErrorBanner, Loading, Panel, formatDate } from "../components/common";
import { ExpressionField } from "../components/ExpressionField";
import {
  useDefineSubscription,
  useDeleteSubscription,
  useEventSubscriptions,
  useEventTypes,
  useRedeliver,
  useSubscriptionHealth,
  useUpdateSubscription,
  useWebhookDeliveries,
  type EventSubscription,
} from "./adminQueries";

const STATUS: Record<string, string> = { DELIVERED: "tag allow", PENDING: "tag warn", DEAD: "tag deny" };

/**
 * Event webhooks (roadmap 2.6): who's told about what, and how each delivery went. Subscriptions
 * of the site are edited here; extensions' are listed with their source.
 */
export function WebhooksAdmin() {
  const { data: subscriptions, isLoading, error } = useEventSubscriptions();
  const { data: health } = useSubscriptionHealth();
  const remove = useDeleteSubscription();
  const [editing, setEditing] = useState<EventSubscription | "new" | null>(null);
  const [selected, setSelected] = useState("");
  const healthOf = new Map((health ?? []).map((h) => [h.subscriptionId, h]));

  if (isLoading) return <Loading />;
  return (
    <div style={{ display: "grid", gap: 12 }}>
      <Panel
        title="Event subscriptions"
        note="Events are queued with the change that caused them, sent signed, retried with growing waits, then set aside as dead"
        flush
        actions={
          <button className="btn small primary" onClick={() => setEditing("new")}>
            <Plus size={13} aria-hidden />
            New subscription
          </button>
        }
      >
        <ErrorBanner error={error ?? remove.error} />
        {!subscriptions?.length ? (
          <p className="empty">No subscriptions yet. A subscription sends the events you choose to a web address, as they happen.</p>
        ) : (
          <table className="grid" aria-label="Event subscriptions">
            <thead>
              <tr>
                <th>Subscription</th>
                <th>Events</th>
                <th>Sends to</th>
                <th>Deliveries</th>
                <th>From</th>
                <th aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {subscriptions.map((s) => {
                const h = healthOf.get(s.id);
                return (
                  <tr key={s.id} className={s.active ? undefined : "inactive"}>
                    <td>
                      <button className="link-btn" onClick={() => setSelected(s.id)} title="Show its deliveries">
                        <strong>{s.name}</strong>
                      </button>
                      {s.filter && (
                        <div className="muted small">
                          when <code>{s.filter}</code>
                        </div>
                      )}
                      {!s.active && <div className="muted small">Inactive</div>}
                    </td>
                    <td>
                      <div className="row wrap" style={{ gap: 4 }}>
                        {s.events.map((e) => (
                          <code key={e} className="tag">
                            {e}
                          </code>
                        ))}
                      </div>
                    </td>
                    <td>
                      <code className="wrap-code">{s.url}</code>
                    </td>
                    <td className="nowrap">
                      <span className="tag allow">{h?.delivered ?? 0} delivered</span>{" "}
                      {(h?.pending ?? 0) > 0 && <span className="tag warn">{h!.pending} pending</span>}{" "}
                      {(h?.dead ?? 0) > 0 && <span className="tag deny">{h!.dead} dead</span>}
                    </td>
                    <td className="nowrap">{s.source === "site" ? "This site" : <code>{s.source}</code>}</td>
                    <td className="nowrap">
                      {s.source === "site" ? (
                        <>
                          <button className="icon-btn" aria-label={`Edit ${s.name}`} onClick={() => setEditing(s)}>
                            <Pencil size={13} />
                          </button>
                          <button
                            className="icon-btn"
                            aria-label={`Delete ${s.name}`}
                            disabled={remove.isPending}
                            onClick={() =>
                              window.confirm(`Delete the subscription “${s.name}”? Its deliveries stay in the log.`) && remove.mutate(s.id)
                            }
                          >
                            <Trash2 size={13} />
                          </button>
                        </>
                      ) : (
                        <span className="muted small">Extension</span>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </Panel>
      <Deliveries subscriptions={subscriptions ?? []} selected={selected} onSelect={setSelected} />
      {editing && <SubscriptionDialog subscription={editing === "new" ? null : editing} onClose={() => setEditing(null)} />}
    </div>
  );
}

/** The delivery log, refreshed every few seconds, with Redeliver for what failed. */
function Deliveries({
  subscriptions,
  selected,
  onSelect,
}: {
  subscriptions: EventSubscription[];
  selected: string;
  onSelect: (id: string) => void;
}) {
  const [status, setStatus] = useState("");
  const { data: deliveries } = useWebhookDeliveries(selected, status);
  const redeliver = useRedeliver();
  return (
    <Panel
      title="Deliveries"
      note={deliveries ? `latest ${deliveries.length}` : undefined}
      flush
      actions={
        <>
          <select className="filter" aria-label="Subscription" value={selected} onChange={(e) => onSelect(e.target.value)}>
            <option value="">All subscriptions</option>
            {subscriptions.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
              </option>
            ))}
          </select>
          <select className="filter" aria-label="Status" value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="">Any status</option>
            <option value="PENDING">Pending</option>
            <option value="DELIVERED">Delivered</option>
            <option value="DEAD">Dead</option>
          </select>
        </>
      }
    >
      <ErrorBanner error={redeliver.error} />
      {!deliveries?.length ? (
        <p className="empty">Nothing delivered yet.</p>
      ) : (
        <table className="grid" aria-label="Deliveries">
          <thead>
            <tr>
              <th>Queued</th>
              <th>Subscription</th>
              <th>Event</th>
              <th>Status</th>
              <th>Attempts</th>
              <th>Last answer</th>
              <th>Next try</th>
              <th aria-label="Actions" />
            </tr>
          </thead>
          <tbody>
            {deliveries.map((d) => (
              <tr key={d.id}>
                <td className="nowrap muted">{formatDate(d.createdAt)}</td>
                <td className="nowrap">{d.subscriptionName}</td>
                <td className="nowrap">
                  <code>{d.eventType.replace(/\.v\d+$/, "")}</code>
                </td>
                <td>
                  <span className={STATUS[d.status] ?? "tag"}>{d.status.toLowerCase()}</span>
                </td>
                <td className="nowrap">
                  {d.attempts} of {d.maxAttempts}
                </td>
                <td>
                  {d.status === "DELIVERED"
                    ? `${d.lastHttpStatus} OK in ${d.durationMs} ms`
                    : d.lastHttpStatus != null
                      ? `Answered ${d.lastHttpStatus}`
                      : (d.lastError ?? "")}
                </td>
                <td className="nowrap muted">{d.status === "PENDING" && d.nextAttemptAt ? formatDate(d.nextAttemptAt) : "—"}</td>
                <td className="nowrap">
                  {d.status !== "DELIVERED" && (
                    <button
                      className="btn small"
                      disabled={redeliver.isPending}
                      title="Send it again now, from the first attempt"
                      onClick={() => redeliver.mutate(d.id)}
                    >
                      <RotateCcw size={12} aria-hidden />
                      Redeliver
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Panel>
  );
}

function SubscriptionDialog({ subscription, onClose }: { subscription: EventSubscription | null; onClose: () => void }) {
  const { data: eventTypes } = useEventTypes();
  const define = useDefineSubscription();
  const update = useUpdateSubscription();
  const [name, setName] = useState(subscription?.name ?? "");
  const [events, setEvents] = useState<string[]>(subscription?.events ?? ["versioning.ObjectStateChanged.v1"]);
  // Event types beyond the listed ones; untouched, it shows (and keeps) the subscription's own.
  const [other, setOther] = useState<string | null>(null);
  const [filter, setFilter] = useState(subscription?.filter ?? "");
  const [url, setUrl] = useState(subscription?.url ?? "");
  const [secret, setSecret] = useState("");
  const [maxAttempts, setMaxAttempts] = useState(subscription?.maxAttempts ?? 8);
  const [active, setActive] = useState(subscription?.active ?? true);
  const known = new Set((eventTypes ?? []).map((t) => t.type));
  const custom = events.filter((e) => !known.has(e));
  const toggle = (e: string) => setEvents(events.includes(e) ? events.filter((x) => x !== e) : [...events, e]);
  const saving = define.isPending || update.isPending;

  const save = () => {
    const extra = (other ?? custom.join(", "))
      .split(",")
      .map((x) => x.trim())
      .filter(Boolean);
    const body = {
      name,
      events: [...events.filter((e) => known.has(e)), ...extra],
      filter: filter.trim() || null,
      url,
      secret: secret || null,
      maxAttempts,
      active,
    };
    if (subscription) update.mutate({ id: subscription.id, body }, { onSuccess: onClose });
    else define.mutate(body, { onSuccess: onClose });
  };

  return (
    <Dialog
      open
      wide
      title={subscription ? `Edit ${subscription.name}` : "New event subscription"}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button className="btn primary" disabled={saving || !name.trim() || !url.trim() || (!subscription && !secret)} onClick={save}>
            Save
          </button>
        </>
      }
    >
      <div className="attr-form">
        <ErrorBanner error={define.error ?? update.error} />
        <div className="field">
          <label htmlFor="sub-name">Name</label>
          <input id="sub-name" value={name} onChange={(e) => setName(e.target.value)} autoFocus />
        </div>
        <div className="field">
          <label htmlFor="sub-url">Send to</label>
          <input
            id="sub-url"
            type="url"
            value={url}
            onChange={(e) => setUrl(e.target.value)}
            placeholder="https://erp.example.com/plm-events"
          />
        </div>
        <fieldset className="check-list wide">
          <legend>Events</legend>
          {eventTypes?.map((t) => (
            <label key={t.type} className="check" title={t.type}>
              <input type="checkbox" checked={events.includes(t.type)} onChange={() => toggle(t.type)} />
              {t.description}
            </label>
          ))}
        </fieldset>
        <div className="field wide">
          <label htmlFor="sub-other">Other event types</label>
          <input
            id="sub-other"
            value={other ?? custom.join(", ")}
            onChange={(e) => setOther(e.target.value)}
            placeholder="Comma-separated, wildcards allowed: versioning.*, *"
          />
        </div>
        <ExpressionField
          id="sub-filter"
          label="Only when"
          value={filter}
          onChange={setFilter}
          tryable={false}
          placeholder="event.to == 'RELEASED'"
          hint="Optional. A condition on the event: its fields (e.g. event.to, event.number, event.typeId) and event.type, event.actor."
        />
        <div className="field">
          <label htmlFor="sub-secret">Signing secret</label>
          <input
            id="sub-secret"
            type="password"
            autoComplete="off"
            value={secret}
            onChange={(e) => setSecret(e.target.value)}
            placeholder={subscription ? "Set: leave empty to keep it" : "Shared with the receiver"}
          />
          <span className="hint">Requests carry X-PLM-Signature: an HMAC-SHA256 of the timestamp and body with this secret.</span>
        </div>
        <div className="field">
          <label htmlFor="sub-attempts">Attempts before giving up</label>
          <input
            id="sub-attempts"
            type="number"
            min={1}
            max={20}
            value={maxAttempts}
            onChange={(e) => setMaxAttempts(Number(e.target.value))}
          />
          <span className="hint">Waits double between tries: 10 s, 20 s, 40 s… at most an hour.</span>
        </div>
        <label className="check wide">
          <input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} />
          Active
        </label>
      </div>
    </Dialog>
  );
}
