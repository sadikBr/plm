import {
  ArrowRightLeft,
  Bookmark,
  BookmarkX,
  ClipboardPlus,
  Copy,
  CopyPlus,
  FolderInput,
  Trash2,
  UserRoundCog,
  GitCompare,
  GitBranchPlus,
  Link2,
  LockOpen,
  MoreHorizontal,
  PencilLine,
  Play,
  Send,
  ShieldAlert,
  Undo2,
  Upload,
  Workflow,
  type LucideIcon,
} from "lucide-react";
import { Fragment, type ReactNode } from "react";
import type { AvailableAction } from "../api/queries";
import { t } from "../i18n/i18n";
import { MenuButton, StateBadge } from "../components/common";

/** Icons an action may name (registry `icon`). Unknown names fall back to a generic one. */
export const ACTION_ICONS: Record<string, LucideIcon> = {
  "lock-open": LockOpen,
  upload: Upload,
  "undo-2": Undo2,
  "git-branch-plus": GitBranchPlus,
  "arrow-right-left": ArrowRightLeft,
  copy: Copy,
  "link-2": Link2,
  "pencil-line": PencilLine,
  workflow: Workflow,
  send: Send,
  play: Play,
  "copy-plus": CopyPlus,
  "git-compare": GitCompare,
  "clipboard-plus": ClipboardPlus,
  bookmark: Bookmark,
  "bookmark-x": BookmarkX,
  "folder-input": FolderInput,
  "user-round-cog": UserRoundCog,
  "trash-2": Trash2,
};

function ActionIcon({ name, size }: { name?: string | null; size: number }) {
  const Icon = (name && ACTION_ICONS[name]) || Play;
  return <Icon size={size} aria-hidden />;
}

/**
 * The label of a built-in action in the interface language. Labels are configuration (Admin →
 * Actions): one an administrator changed, and extension actions, show as the server sends them.
 */
export function actionLabel(a: Pick<AvailableAction, "id" | "label">): string {
  const builtin = (english: string, translated: string) => (a.label === english ? translated : a.label);
  switch (a.id) {
    case "object.check-out":
      return builtin("Check out", t("Check out"));
    case "object.check-in":
      return builtin("Check in", t("Check in"));
    case "object.undo-check-out":
      return builtin("Undo check-out", t("Undo check-out"));
    case "object.revise":
      return builtin("Revise", t("Revise"));
    case "object.set-state":
      return builtin("Set state", t("Set state"));
    case "object.copy-number":
      return builtin("Copy number", t("Copy number"));
    case "object.copy-link":
      return builtin("Copy link", t("Copy link"));
    case "object.rename":
      return builtin("Rename or renumber…", t("Rename or renumber…"));
    case "object.change-lifecycle":
      return builtin("Change lifecycle…", t("Change lifecycle…"));
    case "object.save-as":
      return builtin("Save as…", t("Save as…"));
    case "object.compare":
      return builtin("Compare…", t("Compare…"));
    case "object.add-to-clipboard":
      return builtin("Add to clipboard", t("Add to clipboard"));
    case "object.reserve":
      return builtin("Reserve", t("Reserve"));
    case "object.release":
      return builtin("Release reservation", t("Release reservation"));
    case "object.move":
      return builtin("Move…", t("Move…"));
    case "object.change-owner":
      return builtin("Change owner…", t("Change owner…"));
    case "object.delete":
      return builtin("Delete…", t("Delete…"));
    default:
      return a.label;
  }
}

/**
 * What the page does when an action is chosen. Actions without a handler here (declared by a
 * newer server, or by an extension before custom handlers exist) show disabled.
 */
export type ActionHandlers = Partial<Record<string, (option?: { value: string; override: boolean }) => void>>;

/**
 * The object's toolbar and More menu, drawn from the server's action registry: order, placement,
 * labels and which ones show are configuration (Admin → Actions), not code.
 */
export function ObjectActionBar({
  actions,
  handlers,
  busy,
  stateLabel,
  onInvoke,
}: {
  actions: AvailableAction[];
  handlers: ActionHandlers;
  busy: boolean;
  stateLabel: (state: string) => string;
  /** Runs server-side actions (extension webhooks). */
  onInvoke?: (action: AvailableAction) => void;
}) {
  const toolbar = actions.filter((a) => a.placement === "OBJECT_TOOLBAR");
  const more = actions.filter((a) => a.placement === "OBJECT_MORE");
  // Extension actions that open a web address need no page code; the others are handled here.
  const handlerOf = (a: AvailableAction) =>
    a.handler === "URL"
      ? a.url
        ? () => void window.open(a.url!, "_blank", "noopener,noreferrer")
        : undefined
      : a.handler === "WEBHOOK"
        ? onInvoke && (() => onInvoke(a))
        : handlers[a.id];
  const unavailable = (a: AvailableAction) => !a.enabled || busy || !handlerOf(a);
  const why = (a: AvailableAction) =>
    a.reason ??
    (handlerOf(a)
      ? a.handler === "URL"
        ? t("Opens {url}", { url: a.url ?? "" })
        : undefined
      : t("This action isn't available in this version of the app"));

  const toolbarItem = (a: AvailableAction) =>
    a.options.length > 0 && !unavailable(a) ? (
      <MenuButton key={a.id} label={actionLabel(a)} icon={<ActionIcon name={a.icon} size={15} />} align="left">
        {(close) => <Options action={a} onPick={(o) => (handlers[a.id]?.(o), close())} stateLabel={stateLabel} />}
      </MenuButton>
    ) : (
      <button key={a.id} className="btn ghost" data-action={a.id} disabled={unavailable(a)} title={why(a)} onClick={() => handlerOf(a)?.()}>
        <ActionIcon name={a.icon} size={15} />
        {actionLabel(a)}
      </button>
    );

  return (
    <>
      {toolbar.map((a, i) => (
        <Fragment key={a.id}>
          {i > 0 && toolbar[i - 1]!.group !== a.group && <span className="divider" />}
          {toolbarItem(a)}
        </Fragment>
      ))}
      <span className="grow" />
      {more.length > 0 && (
        <MenuButton label={t("More")} icon={<MoreHorizontal size={15} aria-hidden />}>
          {(close) =>
            more.map((a, i) => (
              <Fragment key={a.id}>
                {i > 0 && more[i - 1]!.group !== a.group && <hr />}
                {a.options.length > 0 ? (
                  <>
                    <div className="menu-label">{actionLabel(a)}</div>
                    <Options action={a} onPick={(o) => (handlers[a.id]?.(o), close())} stateLabel={stateLabel} />
                  </>
                ) : (
                  <button
                    role="menuitem"
                    data-action={a.id}
                    disabled={unavailable(a)}
                    title={why(a)}
                    onClick={() => {
                      handlerOf(a)?.();
                      close();
                    }}
                  >
                    <ActionIcon name={a.icon} size={14} /> {actionLabel(a)}
                  </button>
                )}
              </Fragment>
            ))
          }
        </MenuButton>
      )}
    </>
  );
}

/** The choices of an action with options (Set state's target states). */
function Options({
  action,
  onPick,
  stateLabel,
}: {
  action: AvailableAction;
  onPick: (option: { value: string; override: boolean }) => void;
  stateLabel: (state: string) => string;
}): ReactNode {
  if (!action.enabled) return <button disabled>{action.reason}</button>;
  const normal = action.options.filter((o) => !o.override);
  const overrides = action.options.filter((o) => o.override);
  return (
    <>
      {normal.map((o) => (
        <button key={o.value} role="menuitem" onClick={() => onPick(o)}>
          <StateBadge state={o.value} label={stateLabel(o.value) || o.label} />
        </button>
      ))}
      {overrides.length > 0 && (
        <>
          {normal.length > 0 && <hr />}
          {overrides.map((o) => (
            <button
              key={o.value}
              role="menuitem"
              title={t("Site administrator override: ignores the lifecycle's transitions")}
              onClick={() => onPick(o)}
            >
              <ShieldAlert size={14} aria-hidden /> {t("{state} (override)", { state: o.label })}
            </button>
          ))}
        </>
      )}
    </>
  );
}
