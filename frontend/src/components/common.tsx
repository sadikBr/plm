import { useEffect, useRef, type ReactNode } from "react";
import { ApiError } from "../api/client";
import { usePrincipal, type ContextKind } from "../api/queries";

/** A user's display name, resolved from their id (cached). */
export function PrincipalName({ id }: { id: string | null | undefined }) {
  const { data } = usePrincipal(id);
  if (!id) return <span className="muted">—</span>;
  return <span title={id}>{data?.displayName ?? shortId(id)}</span>;
}

export function shortId(id: string): string {
  return id.length > 12 ? `${id.slice(0, 8)}…` : id;
}

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

const KIND_LABEL: Record<ContextKind, string> = {
  SITE: "Site",
  ORGANIZATION: "Organization",
  PRODUCT: "Product",
  LIBRARY: "Library",
  PROJECT: "Project",
};

export function kindLabel(kind: ContextKind): string {
  return KIND_LABEL[kind];
}

export function KindBadge({ kind }: { kind: ContextKind }) {
  return <span className="badge">{kindLabel(kind)}</span>;
}

export function formatDate(iso: string | null | undefined): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
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
