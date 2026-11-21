import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronRight, ClipboardPaste, Folder as FolderIcon, FolderPlus, Pencil, Trash2 } from "lucide-react";
import { useState } from "react";
import { api, unwrap, type Schemas } from "../api/client";
import { useClipboard } from "../components/Clipboard";
import { Dialog, ErrorBanner, MenuButton } from "../components/common";

export type Folder = Schemas["Folder"];

export const useFolders = (contextId: string, parentId?: string) =>
  useQuery({
    queryKey: ["folders", contextId, parentId ?? ""],
    queryFn: () => unwrap(api.GET("/api/v1/contexts/{contextId}/folders", { params: { path: { contextId }, query: { parentId } } })),
  });

export const useFolderPath = (folderId?: string) =>
  useQuery({
    queryKey: ["folderPath", folderId],
    queryFn: () => unwrap(api.GET("/api/v1/folders/{id}/path", { params: { path: { id: folderId! } } })),
    enabled: !!folderId,
  });

/**
 * A context's folders (roadmap 3.4): where you are, the folders here, and pasting what's on the
 * clipboard (moved or copied here).
 */
export function FolderBar({
  contextId,
  contextName,
  folderId,
  onOpen,
  canCreate,
}: {
  contextId: string;
  contextName: string;
  folderId?: string;
  onOpen: (folderId?: string) => void;
  canCreate: boolean;
}) {
  const qc = useQueryClient();
  const { data: folders } = useFolders(contextId, folderId);
  const { data: path } = useFolderPath(folderId);
  const clipboard = useClipboard();
  const [dialog, setDialog] = useState<{ kind: "new" } | { kind: "rename"; folder: Folder } | null>(null);
  const refresh = () => {
    void qc.invalidateQueries({ queryKey: ["folders", contextId] });
    void qc.invalidateQueries({ queryKey: ["objects", contextId] });
    void qc.invalidateQueries({ queryKey: ["folderPath"] });
  };
  const remove = useMutation({
    mutationFn: (id: string) => unwrap(api.DELETE("/api/v1/folders/{id}", { params: { path: { id } } })),
    onSuccess: refresh,
  });
  const paste = useMutation({
    mutationFn: async (how: "move" | "copy") => {
      const done: string[] = [];
      for (const item of clipboard.items) {
        if (how === "move") {
          await unwrap(
            api.POST("/api/v1/objects/{id}/move", { params: { path: { id: item.id } }, body: { contextId, folderId: folderId ?? null } }),
          );
        } else {
          const copy = await unwrap(
            api.POST("/api/v1/objects/{id}/save-as", {
              params: { path: { id: item.id } },
              body: { name: item.name, contextId, withFiles: true },
            }),
          );
          if (folderId)
            await unwrap(api.POST("/api/v1/objects/{id}/move", { params: { path: { id: copy.id } }, body: { contextId, folderId } }));
        }
        done.push(item.id);
      }
      return { how, done };
    },
    onSuccess: ({ how, done }) => {
      if (how === "move") done.forEach(clipboard.remove);
      refresh();
    },
    onSettled: refresh,
  });

  return (
    <div className="folder-bar">
      <div className="row" style={{ gap: 6, padding: "8px 12px", borderBottom: "1px solid var(--border)", flexWrap: "wrap" }}>
        <nav className="folder-path" aria-label="Folder">
          <button className="link-btn" onClick={() => onOpen(undefined)}>
            {contextName}
          </button>
          {path?.map((f) => (
            <span key={f.id}>
              <ChevronRight size={12} className="icon-muted" aria-hidden />
              <button className="link-btn" onClick={() => onOpen(f.id)} aria-current={f.id === folderId ? "page" : undefined}>
                {f.name}
              </button>
            </span>
          ))}
        </nav>
        <span className="grow" />
        {clipboard.items.length > 0 && (
          <MenuButton label={`Paste ${clipboard.items.length} here`} icon={<ClipboardPaste size={13} aria-hidden />} className="btn small">
            {(close) => (
              <>
                <button role="menuitem" onClick={() => (paste.mutate("move"), close())}>
                  Move here
                </button>
                <button role="menuitem" onClick={() => (paste.mutate("copy"), close())}>
                  Copy here (Save As)
                </button>
              </>
            )}
          </MenuButton>
        )}
        {canCreate && (
          <button className="btn small" onClick={() => setDialog({ kind: "new" })}>
            <FolderPlus size={13} aria-hidden />
            New folder
          </button>
        )}
      </div>
      <ErrorBanner error={remove.error ?? paste.error} />
      {!!folders?.length && (
        <table className="grid folders" aria-label="Folders">
          <tbody>
            {folders.map((f) => (
              <tr key={f.id} className="clickable" onClick={() => onOpen(f.id)}>
                <td>
                  <span className="row" style={{ gap: 8 }}>
                    <FolderIcon size={16} className="folder-icon" aria-hidden />
                    <strong>{f.name}</strong>
                  </span>
                </td>
                <td className="muted nowrap">
                  {f.folders > 0 && `${f.folders} folder${f.folders === 1 ? "" : "s"} · `}
                  {f.objects} object{f.objects === 1 ? "" : "s"}
                </td>
                <td className="nowrap" style={{ width: 1 }} onClick={(e) => e.stopPropagation()}>
                  <button className="icon-btn" aria-label={`Rename ${f.name}`} onClick={() => setDialog({ kind: "rename", folder: f })}>
                    <Pencil size={13} />
                  </button>
                  <button
                    className="icon-btn"
                    aria-label={`Delete ${f.name}`}
                    disabled={f.folders + f.objects > 0}
                    title={f.folders + f.objects > 0 ? "Only empty folders can be deleted" : "Delete"}
                    onClick={() => remove.mutate(f.id)}
                  >
                    <Trash2 size={13} />
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {dialog && (
        <FolderDialog
          contextId={contextId}
          parentId={folderId}
          folder={dialog.kind === "rename" ? dialog.folder : null}
          onClose={() => setDialog(null)}
          onDone={refresh}
        />
      )}
    </div>
  );
}

function FolderDialog({
  contextId,
  parentId,
  folder,
  onClose,
  onDone,
}: {
  contextId: string;
  parentId?: string;
  folder: Folder | null;
  onClose: () => void;
  onDone: () => void;
}) {
  const [name, setName] = useState(folder?.name ?? "");
  const save = useMutation({
    mutationFn: () =>
      folder
        ? unwrap(api.PUT("/api/v1/folders/{id}", { params: { path: { id: folder.id } }, body: { name } }))
        : unwrap(
            api.POST("/api/v1/contexts/{contextId}/folders", {
              params: { path: { contextId } },
              body: { name, parentId: parentId ?? null },
            }),
          ),
    onSuccess: () => {
      onDone();
      onClose();
    },
  });
  return (
    <Dialog
      open
      title={folder ? `Rename ${folder.name}` : "New folder"}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button className="btn primary" disabled={save.isPending || !name.trim()} onClick={() => save.mutate()}>
            {folder ? "Rename" : "Create"}
          </button>
        </>
      }
    >
      <div className="attr-form">
        <ErrorBanner error={save.error} />
        <div className="field wide">
          <label htmlFor="folder-name">Name</label>
          <input
            id="folder-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            autoFocus
            onKeyDown={(e) => e.key === "Enter" && name.trim() && save.mutate()}
          />
        </div>
      </div>
    </Dialog>
  );
}

/** Choose a context (by name) and a folder in it. */
export function FolderPicker({
  contextId,
  folderId,
  onChange,
}: {
  contextId: string;
  folderId?: string;
  onChange: (contextId: string, folderId?: string) => void;
}) {
  const [query, setQuery] = useState("");
  const { data: contexts } = useQuery({
    queryKey: ["contextSearch", query],
    queryFn: () => unwrap(api.GET("/api/v1/contexts", { params: { query: { q: query.trim(), limit: 8 } } })),
    enabled: query.trim().length >= 2,
  });
  const { data: here } = useQuery({
    queryKey: ["contextById", contextId],
    queryFn: () => unwrap(api.GET("/api/v1/contexts/{id}", { params: { path: { id: contextId } } })),
  });
  const { data: path } = useFolderPath(folderId);
  const { data: folders } = useFolders(contextId, folderId);
  return (
    <div className="folder-picker">
      <div className="field wide">
        <label htmlFor="move-context">Context</label>
        <input
          id="move-context"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder={here ? here.name : "Search by name"}
        />
        {!!contexts?.length && (
          <div className="pick-list" role="listbox" aria-label="Contexts">
            {contexts
              .filter((c) => c.kind !== "SITE" && c.kind !== "ORGANIZATION")
              .map((c) => (
                <button
                  key={c.id}
                  role="option"
                  aria-selected={c.id === contextId}
                  className={c.id === contextId ? "selected" : undefined}
                  onClick={() => {
                    onChange(c.id, undefined);
                    setQuery("");
                  }}
                >
                  <strong>{c.name}</strong> <span className="muted">{c.kind.toLowerCase()}</span>
                </button>
              ))}
          </div>
        )}
      </div>
      <div className="field wide">
        <span className="label">Folder</span>
        <nav className="folder-path" aria-label="Destination folder">
          <button className="link-btn" onClick={() => onChange(contextId, undefined)}>
            {here?.name ?? "…"}
          </button>
          {path?.map((f) => (
            <span key={f.id}>
              <ChevronRight size={12} className="icon-muted" aria-hidden />
              <button className="link-btn" onClick={() => onChange(contextId, f.id)}>
                {f.name}
              </button>
            </span>
          ))}
        </nav>
        <div className="pick-list" role="listbox" aria-label="Folders">
          {folders?.map((f) => (
            <button key={f.id} role="option" aria-selected={false} onClick={() => onChange(contextId, f.id)}>
              <FolderIcon size={13} className="folder-icon" aria-hidden /> {f.name}
            </button>
          ))}
          {folders?.length === 0 && <span className="muted small">No folders inside.</span>}
        </div>
      </div>
    </div>
  );
}
