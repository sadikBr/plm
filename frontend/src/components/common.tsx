import { language, locale, t } from "../i18n/i18n";
import {
  Bolt,
  Box,
  Boxes,
  DraftingCompass,
  Shapes,
  ClipboardList,
  Building2,
  Cable,
  ChevronDown,
  CircuitBoard,
  Cog,
  Cpu,
  Drill,
  FileText,
  FlaskConical,
  FolderKanban,
  Gauge,
  Globe,
  Layers,
  Library,
  Package,
  Ruler,
  Shield,
  Wrench,
  type LucideIcon,
} from "lucide-react";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { ApiError } from "../api/client";
import { usePrincipal, useTypes, type ContextKind } from "../api/queries";

// ---------- People ----------

const SYSTEM = "system";

/** A user's display name, resolved from their id (cached). */
export function PrincipalName({ id }: { id: string | null | undefined }) {
  // Seed data and automatic changes are by "system", which isn't a user to look up.
  const { data } = usePrincipal(id === SYSTEM ? undefined : id);
  if (!id) return <span className="muted">—</span>;
  if (id === SYSTEM) return <span className="muted">{t("System")}</span>;
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

export function Spinner({ label = t("Loading") }: { label?: string }) {
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
  return new Date(iso).toLocaleString(locale(), { dateStyle: "medium", timeStyle: "short" });
}

// ---------- Contexts, types and states ----------

const KIND: Record<ContextKind, { slug: string; icon: LucideIcon }> = {
  SITE: { slug: "site", icon: Globe },
  ORGANIZATION: { slug: "organizations", icon: Building2 },
  PRODUCT: { slug: "products", icon: Package },
  LIBRARY: { slug: "libraries", icon: Library },
  PROJECT: { slug: "projects", icon: FolderKanban },
};

export function kindLabel(kind: ContextKind): string {
  switch (kind) {
    case "SITE":
      return t("Site");
    case "ORGANIZATION":
      return t("Organization");
    case "PRODUCT":
      return t("Product");
    case "LIBRARY":
      return t("Library");
    case "PROJECT":
      return t("Project");
  }
}

export function kindPlural(kind: ContextKind): string {
  switch (kind) {
    case "SITE":
      return t("Site");
    case "ORGANIZATION":
      return t("Organizations");
    case "PRODUCT":
      return t("Products");
    case "LIBRARY":
      return t("Libraries");
    case "PROJECT":
      return t("Projects");
  }
}

/** A kind's name for use inside a sentence: lower case, except in German where nouns keep their capital. */
export function kindInText(kind: ContextKind): string {
  const label = kindLabel(kind);
  return language() === "de" ? label : label.toLowerCase();
}

/** A kind's plural for use inside a sentence (see [kindInText]). */
export function kindPluralInText(kind: ContextKind): string {
  const plural = kindPlural(kind);
  return language() === "de" ? plural : plural.toLowerCase();
}

/** The kinds that are browsed as lists, in sidebar order; the site is a single context. */
export const BROWSABLE_KINDS: ContextKind[] = ["ORGANIZATION", "PRODUCT", "LIBRARY", "PROJECT"];

/** URL of the list of every visible context of [kind], e.g. `/browse/products`. */
export function browsePath(kind: ContextKind): string {
  return `/browse/${KIND[kind].slug}`;
}

export function kindFromSlug(slug: string | undefined): ContextKind | undefined {
  return BROWSABLE_KINDS.find((k) => KIND[k].slug === slug);
}

export function ContextIcon({ kind, size = 15 }: { kind: ContextKind; size?: number }) {
  const Icon = KIND[kind].icon;
  return <Icon size={size} className="icon-muted" aria-hidden />;
}

/** Icons an administrator can give a type (Admin → Types → Properties). */
export const TYPE_ICONS: Record<string, LucideIcon> = {
  box: Box,
  boxes: Boxes,
  shapes: Shapes,
  "drafting-compass": DraftingCompass,
  "file-text": FileText,
  bolt: Bolt,
  cog: Cog,
  cpu: Cpu,
  "circuit-board": CircuitBoard,
  cable: Cable,
  layers: Layers,
  package: Package,
  wrench: Wrench,
  drill: Drill,
  ruler: Ruler,
  gauge: Gauge,
  flask: FlaskConical,
  shield: Shield,
};

/** A type's icon: its own if an administrator chose one, else its base class's. */
export function TypeIcon({ typeId, size = 15 }: { typeId: string; size?: number }) {
  const { data: types } = useTypes();
  const type = types?.find((t) => t.id === typeId);
  const Icon =
    (type?.icon && TYPE_ICONS[type.icon]) ||
    (type?.baseClass === "DOCUMENT" ? FileText : type?.baseClass === "CHANGE" ? ClipboardList : Box);
  return <Icon size={size} className="type-icon" aria-hidden />;
}

/** A type's name in the user's language when translated, else its display name. */
export function typeLabel(type: { displayName: string; labels?: Record<string, string> } | undefined): string {
  if (!type) return "";
  const labels = type.labels ?? {};
  return labels[language()] ?? type.displayName;
}

export function useTypeName(typeId: string): string {
  const { data: types } = useTypes();
  return typeLabel(types?.find((t) => t.id === typeId));
}

/** `IN_WORK` → `In Work`. */
export function stateLabel(state: string): string {
  switch (state) {
    case "IN_WORK":
      return t("In Work");
    case "UNDER_REVIEW":
      return t("Under Review");
    case "RELEASED":
      return t("Released");
    case "CANCELED":
      return t("Canceled");
    case "CANCELLED":
      return t("Cancelled");
    case "OBSOLETE":
      return t("Obsolete");
    case "DRAFT":
      return t("Draft");
    case "APPROVED":
      return t("Approved");
    case "REJECTED":
      return t("Rejected");
    case "PRIVATE":
      return t("Private");
    case "FROZEN":
      return t("Frozen");
  }
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
  className,
}: {
  title: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  footer?: ReactNode;
  flush?: boolean;
  note?: ReactNode;
  className?: string;
}) {
  return (
    <section className={className ? `panel ${className}` : "panel"}>
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

/**
 * What a list holds before there's anything in it: what it's for, and how to start. Better than a
 * blank box.
 */
export function EmptyState({ icon, title, hint, children }: { icon?: ReactNode; title: string; hint?: ReactNode; children?: ReactNode }) {
  return (
    <div className="empty-state">
      {icon && <span className="empty-state-icon">{icon}</span>}
      <strong>{title}</strong>
      {hint && <p>{hint}</p>}
      {children}
    </div>
  );
}

/** A name and a button that makes something with it: they line up and keep their size anywhere. */
export function CreateBar({
  label,
  placeholder,
  value,
  onChange,
  onCreate,
  busy = false,
  button,
  icon,
  children,
}: {
  label: string;
  placeholder?: string;
  value: string;
  onChange: (v: string) => void;
  onCreate: () => void;
  busy?: boolean;
  button: string;
  icon?: ReactNode;
  children?: ReactNode;
}) {
  return (
    <form
      className="create-bar"
      onSubmit={(e) => {
        e.preventDefault();
        if (value.trim()) onCreate();
      }}
    >
      <input aria-label={label} placeholder={placeholder} value={value} onChange={(e) => onChange(e.target.value)} />
      {children}
      <button className="btn primary" type="submit" disabled={!value.trim() || busy}>
        {icon}
        {button}
      </button>
    </form>
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
  wide = false,
}: {
  open: boolean;
  title: string;
  onClose: () => void;
  children: ReactNode;
  footer: ReactNode;
  /** For dialogs with tables (e.g. an extension's preview). */
  wide?: boolean;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open && !dialog.open) dialog.showModal?.();
    if (!open && dialog.open) dialog.close?.();
  }, [open]);
  return (
    <dialog ref={ref} onClose={onClose} aria-labelledby="dialog-title" className={wide ? "wide" : undefined}>
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
