import {
  Box,
  Building2,
  ChevronDown,
  FileText,
  FolderKanban,
  Globe,
  Library,
  Package,
  type LucideIcon,
} from "lucide-react";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { ApiError } from "../api/client";
import { usePrincipal, useTypes, type ContextKind } from "../api/queries";

// ---------- People ----------

/** A user's display name, resolved from their id (cached). */
export function PrincipalName({ id }: { id: string | null | undefined }) {
  const { data } = usePrincipal(id);
  if (!id) return <span className="muted">—</span>;
  return <span title={id}>{data?.displayName ?? shortId(id)}</span>;
}

export function shortId(id: string): string {
  return id.length > 12 ? `${id.slice(0, 8)}…` : id;
}

export function initials(name: string | undefined): string {
  if (!name) return "?";
  const parts = name.trim().split(/\s+/);
  return ((parts[0]?.[0] ?? "") + (parts.length > 1 ? (parts[parts.length - 1]?.[0] ?? "") : "")).toUpperCase();
}

// ---------- Feedback ----------

export function Spinner({ label = "Loading" }: { label?: string }) {
  return <span className="spinner" role="status" aria-label={label} />;
}

export function Loading() {
  return (
    <div className="empty">
      <Spinner />
    </div>
  );
}

/** Shows an API problem (or any error) in a way users can act on. */
export function ErrorBanner({ error }: { error: unknown }) {
  if (!error) return null;
  const message = error instanceof Error ? error.message : String(error);
  const code = error instanceof ApiError ? error.code : undefined;
  return (
    <div className="banner error" role="alert">
      {message}
      {code && (
        <>
          {" "}
          <code>{code}</code>
        </>
      )}
    </div>
  );
}

export function formatDate(iso: string | null | undefined): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
}

// ---------- Contexts, types and states ----------

const KIND: Record<ContextKind, { label: string; icon: LucideIcon }> = {
  SITE: { label: "Site", icon: Globe },
  ORGANIZATION: { label: "Organization", icon: Building2 },
  PRODUCT: { label: "Product", icon: Package },
  LIBRARY: { label: "Library", icon: Library },
  PROJECT: { label: "Project", icon: FolderKanban },
};

export function kindLabel(kind: ContextKind): string {
  return KIND[kind].label;
}

export function ContextIcon({ kind, size = 15 }: { kind: ContextKind; size?: number }) {
  const Icon = KIND[kind].icon;
  return <Icon size={size} className="icon-muted" aria-hidden />;
}

/** Part, document… icon for a type, from its base class. */
export function TypeIcon({ typeId, size = 15 }: { typeId: string; size?: number }) {
  const { data: types } = useTypes();
  const base = types?.find((t) => t.id === typeId)?.baseClass;
  const Icon = base === "DOCUMENT" ? FileText : Box;
  return <Icon size={size} className="type-icon" aria-hidden />;
}

export function useTypeName(typeId: string): string {
  const { data: types } = useTypes();
  return types?.find((t) => t.id === typeId)?.displayName ?? "";
}

/** `IN_WORK` → `In Work`. */
export function stateLabel(state: string): string {
  return state
    .toLowerCase()
    .split("_")
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(" ");
}

const STATE_CLASS: Record<string, string> = {
  IN_WORK: "in-work",
  UNDER_REVIEW: "review",
  RELEASED: "released",
  CANCELED: "canceled",
  OBSOLETE: "obsolete",
};

/** Lifecycle state with its colour dot. Pass [label] from the lifecycle when known. */
export function StateBadge({ state, label }: { state: string; label?: string }) {
  return <span className={`state ${STATE_CLASS[state] ?? ""}`}>{label ?? stateLabel(state)}</span>;
}

// ---------- Layout building blocks ----------

export function Panel({
  title,
  actions,
  children,
  footer,
  flush = false,
  note,
}: {
  title: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  footer?: ReactNode;
  flush?: boolean;
  note?: ReactNode;
}) {
  return (
    <section className="panel">
      <div className="panel-head">
        <h2>{title}</h2>
        {note && <span className="note">{note}</span>}
        <span className="grow" />
        {actions}
      </div>
      <div className={flush ? "panel-body flush" : "panel-body"}>{children}</div>
      {footer && <div className="panel-foot">{footer}</div>}
    </section>
  );
}

/** Dense label/value pairs. Empty values show a dash. */
export function Props({ items, columns = 1 }: { items: [string, ReactNode][]; columns?: 1 | 2 }) {
  return (
    <dl className={columns === 2 ? "props cols-2" : "props"}>
      {items.map(([label, value]) => {
        const empty = value === null || value === undefined || value === "";
        return (
          <div className="prop" key={label}>
            <dt title={label}>{label}</dt>
            <dd className={empty ? "is-empty" : undefined}>{empty ? "—" : value}</dd>
          </div>
        );
      })}
    </dl>
  );
}

/** A button that opens a small menu of actions. Closes on outside click or Escape. */
export function MenuButton({
  label,
  icon,
  children,
  className = "btn ghost",
  align = "right",
}: {
  label: ReactNode;
  icon?: ReactNode;
  children: (close: () => void) => ReactNode;
  className?: string;
  align?: "left" | "right";
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    document.addEventListener("mousedown", onDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);
  return (
    <div className="menu-wrap" ref={ref}>
      <button className={className} aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen(!open)}>
        {icon}
        {label}
        <ChevronDown size={13} aria-hidden />
      </button>
      {open && (
        <div className={align === "left" ? "menu left" : "menu"} role="menu">
          {children(() => setOpen(false))}
        </div>
      )}
    </div>
  );
}

/** Modal built on the native <dialog>: focus trapping, Esc and backdrop come for free. */
export function Dialog({
  open,
  title,
  onClose,
  children,
  footer,
}: {
  open: boolean;
  title: string;
  onClose: () => void;
  children: ReactNode;
  footer: ReactNode;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open && !dialog.open) dialog.showModal?.();
    if (!open && dialog.open) dialog.close?.();
  }, [open]);
  return (
    <dialog ref={ref} onClose={onClose} aria-labelledby="dialog-title">
      {open && (
        <>
          <header>
            <h2 id="dialog-title">{title}</h2>
          </header>
          <div className="dialog-body">{children}</div>
          <footer>{footer}</footer>
        </>
      )}
    </dialog>
  );
}
