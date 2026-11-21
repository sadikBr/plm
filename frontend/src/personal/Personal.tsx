import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Bell, BellOff, BellRing, CheckCheck, Star } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Link, useNavigate } from "react-router";
import { api, unwrap, type Schemas } from "../api/client";
import { ErrorBanner, Loading, Panel, formatDate } from "../components/common";
import { PageHeader } from "../components/Layout";

// Personal tracking (roadmap 3.9): favorites, recently viewed, subscriptions, notifications.

export type FavoriteKind = Schemas["Favorite"]["kind"];
export type SubscriptionKind = Schemas["Subscription"]["kind"];
export type Notification = Schemas["Notification"];

export const useFavorites = () => useQuery({ queryKey: ["me", "favorites"], queryFn: () => unwrap(api.GET("/api/v1/me/favorites")) });
export const useSubscriptions = () =>
  useQuery({ queryKey: ["me", "subscriptions"], queryFn: () => unwrap(api.GET("/api/v1/me/subscriptions")) });
export const useRecentVisits = (limit = 15) =>
  useQuery({ queryKey: ["me", "recent", limit], queryFn: () => unwrap(api.GET("/api/v1/me/recent", { params: { query: { limit } } })) });
export const useNotifications = (unread = false, limit = 30) =>
  useQuery({
    queryKey: ["me", "notifications", unread, limit],
    queryFn: () => unwrap(api.GET("/api/v1/me/notifications", { params: { query: { unread, limit } } })),
  });
/** Polled, so the bell picks up what others did. */
export const useUnreadCount = () =>
  useQuery({
    queryKey: ["me", "notifications", "unread"],
    queryFn: () => unwrap(api.GET("/api/v1/me/notifications/unread")),
    refetchInterval: 30_000,
  });

/** Records that the object page was opened (fire and forget). */
export function useRecordVisit(objectId: string | undefined) {
  const qc = useQueryClient();
  useEffect(() => {
    if (!objectId) return;
    void api.POST("/api/v1/me/recent/{objectId}", { params: { path: { objectId } } }).then(() => {
      void qc.invalidateQueries({ queryKey: ["me", "recent"] });
    });
  }, [objectId, qc]);
}

/** A star that adds the target to (or takes it off) the user's favorites. */
export function FavoriteButton({ kind, targetId, label }: { kind: FavoriteKind; targetId: string; label: string }) {
  const qc = useQueryClient();
  const { data } = useFavorites();
  const on = !!data?.some((f) => f.kind === kind && f.targetId === targetId);
  const toggle = useMutation({
    mutationFn: async () => {
      const params = { path: { kind, targetId } };
      if (on) await unwrap(api.DELETE("/api/v1/me/favorites/{kind}/{targetId}", { params }));
      else await unwrap(api.PUT("/api/v1/me/favorites/{kind}/{targetId}", { params, body: { label } }));
    },
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["me", "favorites"] }),
  });
  return (
    <button
      className={on ? "track-btn on" : "track-btn"}
      aria-pressed={on}
      aria-label={on ? "Remove from favorites" : "Add to favorites"}
      title={on ? "Remove from favorites" : "Add to favorites"}
      disabled={!data || toggle.isPending}
      onClick={() => toggle.mutate()}
    >
      <Star size={14} aria-hidden fill={on ? "currentColor" : "none"} />
      {on ? "Favorite" : "Add to favorites"}
    </button>
  );
}

/** Follow / unfollow: notifications about changes to the object, or to anything in the folder or context. */
export function SubscribeButton({ kind, targetId, label }: { kind: SubscriptionKind; targetId: string; label: string }) {
  const qc = useQueryClient();
  const { data } = useSubscriptions();
  const on = !!data?.some((s) => s.kind === kind && s.targetId === targetId);
  const toggle = useMutation({
    mutationFn: async () => {
      const params = { path: { kind, targetId } };
      if (on) await unwrap(api.DELETE("/api/v1/me/subscriptions/{kind}/{targetId}", { params }));
      else await unwrap(api.PUT("/api/v1/me/subscriptions/{kind}/{targetId}", { params, body: { label } }));
    },
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["me", "subscriptions"] }),
  });
  const what = kind === "OBJECT" ? "this object" : kind === "FOLDER" ? "this folder" : "everything here";
  return (
    <button
      className={on ? "track-btn on" : "track-btn"}
      aria-pressed={on}
      title={on ? `Stop notifications about ${what}` : `Get notified when ${what} changes`}
      disabled={!data || toggle.isPending}
      onClick={() => toggle.mutate()}
    >
      {on ? <BellRing size={14} aria-hidden /> : <Bell size={14} aria-hidden />}
      {on ? "Following" : "Follow"}
    </button>
  );
}

function useMarkRead() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (ids: string[]) => unwrap(api.POST("/api/v1/me/notifications/read", { body: { ids } })),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["me", "notifications"] }),
  });
}

function timeAgo(iso: string): string {
  const s = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000);
  if (s < 60) return "just now";
  if (s < 3600) return `${Math.floor(s / 60)} min ago`;
  if (s < 86400) return `${Math.floor(s / 3600)} h ago`;
  return formatDate(iso);
}

function NotificationRow({ n, onOpen }: { n: Notification; onOpen: (n: Notification) => void }) {
  return (
    <li className={n.readAt ? "notice-row" : "notice-row unread"}>
      <span className="notice-dot" aria-hidden />
      <button className="notice-main" onClick={() => onOpen(n)}>
        <span className="notice-title">{n.title}</span>
        <span className="notice-meta">
          {n.body && <>{n.body} · </>}
          {timeAgo(n.createdAt)}
        </span>
      </button>
    </li>
  );
}

/** The top bar's bell: the unread count, and the latest notifications on click. */
export function NotificationBell() {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const navigate = useNavigate();
  const { data: count } = useUnreadCount();
  const { data: latest } = useNotifications(false, 8);
  const markRead = useMarkRead();
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => ref.current && !ref.current.contains(e.target as Node) && setOpen(false);
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    document.addEventListener("mousedown", onDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);
  const unread = count?.unread ?? 0;
  const openNotice = (n: Notification) => {
    if (!n.readAt) markRead.mutate([n.id]);
    setOpen(false);
    if (n.link) void navigate(n.link);
  };
  return (
    <div className="menu-wrap" ref={ref}>
      <button
        className="topbar-link bell-btn"
        aria-label={unread ? `Notifications, ${unread} unread` : "Notifications"}
        aria-haspopup="dialog"
        aria-expanded={open}
        onClick={() => setOpen(!open)}
      >
        <Bell size={15} aria-hidden />
        {unread > 0 && <span className="bell-count">{unread > 99 ? "99+" : unread}</span>}
      </button>
      {open && (
        <div className="menu notice-menu" role="dialog" aria-label="Notifications">
          <div className="notice-head">
            <strong>Notifications</strong>
            <span className="grow" />
            {unread > 0 && (
              <button className="link-btn" onClick={() => markRead.mutate([])}>
                <CheckCheck size={13} aria-hidden /> Mark all read
              </button>
            )}
          </div>
          {!latest ? (
            <Loading />
          ) : latest.length === 0 ? (
            <p className="empty">Nothing yet. Follow an object, folder or product to hear about changes.</p>
          ) : (
            <ul className="notice-list">
              {latest.map((n) => (
                <NotificationRow key={n.id} n={n} onOpen={openNotice} />
              ))}
            </ul>
          )}
          <div className="notice-foot">
            <Link to="/notifications" onClick={() => setOpen(false)}>
              All notifications and subscriptions
            </Link>
          </div>
        </div>
      )}
    </div>
  );
}

const SUBSCRIPTION_LINKS: Record<SubscriptionKind, (id: string) => string> = {
  OBJECT: (id) => `/objects/${id}`,
  CONTEXT: (id) => `/contexts/${id}`,
  FOLDER: () => "",
};

export function favoriteLink(f: Schemas["Favorite"]): string {
  switch (f.kind) {
    case "OBJECT":
      return `/objects/${f.targetId}`;
    case "CONTEXT":
      return `/contexts/${f.targetId}`;
    case "REPORT":
      return `/reports/${f.targetId}`;
    default:
      return "/";
  }
}

/** Everything you were told, what you follow and how you're mailed. */
export function NotificationsPage() {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const [unreadOnly, setUnreadOnly] = useState(false);
  const { data, error } = useNotifications(unreadOnly, 200);
  const { data: subscriptions } = useSubscriptions();
  const { data: settings } = useQuery({
    queryKey: ["me", "notificationSettings"],
    queryFn: () => unwrap(api.GET("/api/v1/me/notification-settings")),
  });
  const markRead = useMarkRead();
  const save = useMutation({
    mutationFn: (mail: Schemas["NotificationSettings"]["mail"]) => unwrap(api.PUT("/api/v1/me/notification-settings", { body: { mail } })),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["me", "notificationSettings"] }),
  });
  const unsubscribe = useMutation({
    mutationFn: (s: Schemas["Subscription"]) =>
      unwrap(api.DELETE("/api/v1/me/subscriptions/{kind}/{targetId}", { params: { path: { kind: s.kind, targetId: s.targetId } } })),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["me", "subscriptions"] }),
  });
  return (
    <>
      <PageHeader
        icon={<Bell size={18} className="icon-muted" aria-hidden />}
        title={<span className="nm">Notifications</span>}
        meta={<span className="sub">Changes to what you follow, and background jobs you started.</span>}
        toolbar={
          <>
            <label className="row" style={{ gap: 6 }}>
              <input type="checkbox" checked={unreadOnly} onChange={(e) => setUnreadOnly(e.target.checked)} /> Unread only
            </label>
            <button className="btn ghost" onClick={() => markRead.mutate([])}>
              <CheckCheck size={15} aria-hidden /> Mark all read
            </button>
          </>
        }
      />
      <div className="content notices-page">
        <ErrorBanner error={error ?? save.error} />
        <Panel title="Inbox" flush>
          {!data ? (
            <Loading />
          ) : data.length === 0 ? (
            <p className="empty">{unreadOnly ? "Nothing unread." : "No notifications yet."}</p>
          ) : (
            <ul className="notice-list wide">
              {data.map((n) => (
                <NotificationRow
                  key={n.id}
                  n={n}
                  onOpen={(x) => {
                    if (!x.readAt) markRead.mutate([x.id]);
                    if (x.link) void navigate(x.link);
                  }}
                />
              ))}
            </ul>
          )}
        </Panel>
        <div className="notices-side">
          <Panel title="Mail me">
            <fieldset className="radio-list" aria-label="Mail me">
              {(
                [
                  ["DAILY", "A daily digest", "One mail each morning with what's new"],
                  ["IMMEDIATE", "Right away", "A mail for each notification"],
                  ["NONE", "Never", "Only here, in the app"],
                ] as const
              ).map(([value, label, hint]) => (
                <label key={value}>
                  <input
                    type="radio"
                    name="mail"
                    checked={settings?.mail === value}
                    disabled={!settings || save.isPending}
                    onChange={() => save.mutate(value)}
                  />
                  <span>
                    <strong>{label}</strong>
                    <span className="muted small"> — {hint}</span>
                  </span>
                </label>
              ))}
            </fieldset>
          </Panel>
          <Panel title="Following" flush>
            {!subscriptions ? (
              <Loading />
            ) : subscriptions.length === 0 ? (
              <p className="empty">You don't follow anything yet.</p>
            ) : (
              <ul className="follow-list">
                {subscriptions.map((s) => (
                  <li key={`${s.kind}:${s.targetId}`}>
                    <span className="tag">{s.kind === "OBJECT" ? "Object" : s.kind === "FOLDER" ? "Folder" : "Context"}</span>
                    {SUBSCRIPTION_LINKS[s.kind](s.targetId) ? (
                      <Link to={SUBSCRIPTION_LINKS[s.kind](s.targetId)} className="grow ellipsis">
                        {s.label}
                      </Link>
                    ) : (
                      <span className="grow ellipsis">{s.label}</span>
                    )}
                    <button
                      className="icon-btn"
                      aria-label={`Stop following ${s.label}`}
                      title="Stop following"
                      onClick={() => unsubscribe.mutate(s)}
                    >
                      <BellOff size={13} />
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </Panel>
        </div>
      </div>
    </>
  );
}
