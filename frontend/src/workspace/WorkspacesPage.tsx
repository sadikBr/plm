import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  AlertTriangle,
  ArrowDownToLine,
  CheckCircle2,
  CircleDot,
  Download,
  FilePlus2,
  HardDrive,
  History,
  Lock,
  LockOpen,
  Plus,
  RefreshCw,
  Search,
  Trash2,
  Undo2,
  Upload,
  UserRound,
  XCircle,
} from "lucide-react";
import { useRef, useState, type ReactNode } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { api, unwrap, type Schemas } from "../api/client";
import { useContextsOfKind, useObjectSearch, useTypes } from "../api/queries";
import { useClipboard } from "../components/Clipboard";
import {
  Dialog,
  EmptyState,
  ErrorBanner,
  Loading,
  MenuButton,
  Panel,
  PrincipalName,
  StateBadge,
  TypeIcon,
  When,
  typeLabel,
} from "../components/common";
import { PageHeader } from "../components/Layout";
import { sendToVault } from "../components/ObjectFiles";
import { t, tn } from "../i18n/i18n";

type Workspace = Schemas["Workspace"];
type Item = Schemas["WorkspaceItem"];
type FileState = Item["fileState"];

/** How each file state looks: a label, an icon and a tone. */
export const FILE_STATES = (): Record<FileState, { label: string; icon: ReactNode; tone: string; hint: string }> => ({
  IN_SYNC: { label: t("In sync"), icon: <CheckCircle2 size={13} />, tone: "ok", hint: t("Your computer has the latest file") },
  OUT_OF_DATE: {
    label: t("Out of date"),
    icon: <ArrowDownToLine size={13} />,
    tone: "warn",
    hint: t("A newer iteration was checked in: update before you edit"),
  },
  MODIFIED: { label: t("Modified"), icon: <CircleDot size={13} />, tone: "info", hint: t("Changed on your computer, not uploaded yet") },
  CHECKED_OUT: { label: t("Checked out"), icon: <LockOpen size={13} />, tone: "accent", hint: t("Checked out to you") },
  CONFLICT: {
    label: t("Conflict"),
    icon: <AlertTriangle size={13} />,
    tone: "danger",
    hint: t("Changed on your computer while someone checked in a newer iteration"),
  },
  CHECKED_OUT_BY_OTHER: { label: t("Someone else's"), icon: <Lock size={13} />, tone: "neutral", hint: t("Checked out by someone else") },
  UNAVAILABLE: { label: t("Unavailable"), icon: <XCircle size={13} />, tone: "neutral", hint: t("You can no longer read it") },
});

export function FileStateBadge({ state }: { state: FileState }) {
  const s = FILE_STATES()[state];
  return (
    <span className={`file-state tone-${s.tone}`} title={s.hint}>
      {s.icon}
      {s.label}
    </span>
  );
}

const size = (n: number | null | undefined) =>
  n == null ? "" : n < 1024 ? `${n} B` : n < 1024 * 1024 ? `${(n / 1024).toFixed(1)} KB` : `${(n / 1024 / 1024).toFixed(1)} MB`;

const useWorkspaces = () => useQuery({ queryKey: ["workspaces"], queryFn: () => unwrap(api.GET("/api/v1/workspaces")) });

/** Personal workspaces (roadmap 8.2): what you work on, and where each file stands. */
export function WorkspacesPage() {
  const { id } = useParams();
  const { data: workspaces, isLoading, error } = useWorkspaces();
  const [creating, setCreating] = useState(false);
  const current = id ?? workspaces?.[0]?.id;
  return (
    <>
      <PageHeader
        icon={<HardDrive size={18} className="icon-muted" aria-hidden />}
        title={<span className="nm">{t("Workspaces")}</span>}
        meta={<span className="sub">{t("The files you work on, and where each stands between your computer and the system.")}</span>}
      />
      <div className="content">
        <ErrorBanner error={error} />
        {isLoading ? (
          <Loading />
        ) : !workspaces?.length ? (
          <Panel title={t("Workspaces")} flush>
            <EmptyState
              icon={<HardDrive size={20} />}
              title={t("No workspace yet")}
              hint={t("A workspace gathers the parts, documents and CAD models you work on, and tracks what changed on your computer.")}
            >
              <button className="btn primary" onClick={() => setCreating(true)}>
                <Plus size={14} aria-hidden /> {t("New workspace")}
              </button>
            </EmptyState>
          </Panel>
        ) : (
          <div className="ws-layout">
            <nav className="ws-list" aria-label={t("Workspaces")}>
              {workspaces.map((w) => (
                <WorkspaceCard key={w.id} ws={w} active={w.id === current} />
              ))}
              <button className="btn ghost ws-new" onClick={() => setCreating(true)}>
                <Plus size={14} aria-hidden /> {t("New workspace")}
              </button>
            </nav>
            {current && <WorkspaceView key={current} id={current} />}
          </div>
        )}
      </div>
      {creating && <WorkspaceDialog onClose={() => setCreating(false)} />}
    </>
  );
}

function WorkspaceCard({ ws, active }: { ws: Workspace; active: boolean }) {
  const counts = ws.counts ?? {};
  const total = Object.values(counts).reduce((a, b) => a + (b ?? 0), 0);
  const attention = (counts.OUT_OF_DATE ?? 0) + (counts.CONFLICT ?? 0) + (counts.MODIFIED ?? 0);
  return (
    <Link to={`/workspaces/${ws.id}`} className={active ? "ws-card active" : "ws-card"} aria-current={active ? "page" : undefined}>
      <span className="ws-card-icon">
        <HardDrive size={16} aria-hidden />
      </span>
      <span className="ws-card-text">
        <strong>{ws.name}</strong>
        <span className="muted small">
          {tn("{n} file", "{n} files", total)}
          {attention > 0 && <span className="ws-card-flag"> · {tn("{n} needs you", "{n} need you", attention)}</span>}
        </span>
      </span>
    </Link>
  );
}

/** Products, libraries and projects, for choosing where new files go. */
function useContainers() {
  const products = useContextsOfKind("PRODUCT").data ?? [];
  const libraries = useContextsOfKind("LIBRARY").data ?? [];
  const projects = useContextsOfKind("PROJECT").data ?? [];
  return [...products, ...libraries, ...projects];
}

function WorkspaceDialog({ ws, onClose }: { ws?: Workspace; onClose: () => void }) {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const containers = useContainers();
  const [name, setName] = useState(ws?.name ?? "");
  const [contextId, setContextId] = useState(ws?.contextId ?? "");
  const [description, setDescription] = useState(ws?.description ?? "");
  const save = useMutation({
    mutationFn: () => {
      const body = { name, contextId, description: description || null };
      return ws
        ? unwrap(api.PUT("/api/v1/workspaces/{id}", { params: { path: { id: ws.id } }, body }))
        : unwrap(api.POST("/api/v1/workspaces", { body }));
    },
    onSuccess: (w) => {
      void qc.invalidateQueries({ queryKey: ["workspaces"] });
      onClose();
      navigate(`/workspaces/${w.id}`);
    },
  });
  return (
    <Dialog
      open
      title={ws ? t("Workspace settings") : t("New workspace")}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            {t("Cancel")}
          </button>
          <button className="btn primary" disabled={!name.trim() || !contextId || save.isPending} onClick={() => save.mutate()}>
            {ws ? t("Save") : t("Create")}
          </button>
        </>
      }
    >
      <div className="attr-form">
        <ErrorBanner error={save.error} />
        <div className="field wide">
          <label htmlFor="ws-name">{t("Name")}</label>
          <input id="ws-name" value={name} onChange={(e) => setName(e.target.value)} autoFocus placeholder={t("e.g. Bracket redesign")} />
        </div>
        <div className="field wide">
          <label htmlFor="ws-context">{t("New files go to")}</label>
          <select id="ws-context" value={contextId} onChange={(e) => setContextId(e.target.value)}>
            <option value="">{t("Choose…")}</option>
            {containers.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
        </div>
        <div className="field wide">
          <label htmlFor="ws-description">{t("Description")}</label>
          <textarea id="ws-description" rows={2} value={description} onChange={(e) => setDescription(e.target.value)} />
        </div>
      </div>
    </Dialog>
  );
}

function WorkspaceView({ id }: { id: string }) {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const { data, error, isLoading } = useQuery({
    queryKey: ["workspaces", id],
    queryFn: () => unwrap(api.GET("/api/v1/workspaces/{id}", { params: { path: { id } } })),
    refetchInterval: 15_000,
  });
  const [filter, setFilter] = useState<FileState | "">("");
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [adding, setAdding] = useState(false);
  const [checkingIn, setCheckingIn] = useState(false);
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [failure, setFailure] = useState<unknown>(null);
  const newFileInput = useRef<HTMLInputElement>(null);

  const refresh = () => {
    void qc.invalidateQueries({ queryKey: ["workspaces"] });
    void qc.invalidateQueries({ queryKey: ["workspaces", id, "history"] });
  };
  const run = async (label: string, work: () => Promise<unknown>) => {
    setBusy(label);
    setFailure(null);
    try {
      await work();
      setSelected(new Set());
    } catch (e) {
      setFailure(e);
    } finally {
      setBusy(null);
      refresh();
    }
  };
  const ids = [...selected];
  const op = (path: "/api/v1/workspaces/{id}/check-out" | "/api/v1/workspaces/{id}/undo", label: string) =>
    run(label, () => unwrap(api.POST(path, { params: { path: { id } }, body: { ids } })));

  /** Downloads the chosen files (or every out-of-date one) and records that this computer now has them. */
  const update = () =>
    run(t("Updating"), async () => {
      const downloads = await unwrap(api.GET("/api/v1/workspaces/{id}/downloads", { params: { path: { id }, query: { ids } } }));
      for (const d of downloads) {
        const a = document.createElement("a");
        a.href = d.url;
        a.download = d.fileName;
        a.click();
      }
      await unwrap(
        api.POST("/api/v1/workspaces/{id}/local", {
          params: { path: { id } },
          body: { files: downloads.map((d) => ({ objectId: d.objectId, iterationId: d.iterationId, sha256: d.sha256 })) },
        }),
      );
    });

  const uploadChange = (item: Item, file: File) =>
    run(t("Uploading"), async () => {
      const uploadId = await sendToVault(file);
      await unwrap(api.POST("/api/v1/workspaces/{id}/upload", { params: { path: { id } }, body: { objectId: item.objectId, uploadId } }));
    });

  const addNewFiles = (files: FileList) =>
    run(t("Uploading"), async () => {
      for (const file of [...files]) {
        const uploadId = await sendToVault(file);
        await unwrap(
          api.POST("/api/v1/workspaces/{id}/new-files", {
            params: { path: { id } },
            body: { uploadId, fileName: file.name, size: file.size },
          }),
        );
      }
    });

  const remove = useMutation({
    mutationFn: () => unwrap(api.DELETE("/api/v1/workspaces/{id}", { params: { path: { id } } })),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["workspaces"] });
      navigate("/workspaces");
    },
  });

  if (isLoading) return <Loading />;
  if (!data) return <ErrorBanner error={error} />;
  const { workspace, items, newFiles } = data;
  const counts = new Map<FileState, number>();
  items.forEach((i) => counts.set(i.fileState, (counts.get(i.fileState) ?? 0) + 1));
  const shown = items.filter((i) => !filter || i.fileState === filter);
  const mine = items.filter((i) => i.fileState === "CHECKED_OUT" || (i.fileState === "MODIFIED" && i.workingFile));
  const toggle = (objectId: string) => {
    const next = new Set(selected);
    if (next.has(objectId)) next.delete(objectId);
    else next.add(objectId);
    setSelected(next);
  };
  const allShown = shown.length > 0 && shown.every((i) => selected.has(i.objectId));

  return (
    <div className="ws-main">
      <section className="ws-head">
        <div className="ws-head-text">
          <h2>{workspace.name}</h2>
          {workspace.description && <p className="muted">{workspace.description}</p>}
          <p className="muted small">
            {t("Updated")} <When iso={workspace.updatedAt} />
          </p>
        </div>
        <div className="ws-head-tools">
          <button className="btn primary" onClick={() => setAdding(true)}>
            <Plus size={14} aria-hidden /> {t("Add objects")}
          </button>
          <button className="btn" onClick={() => newFileInput.current?.click()}>
            <FilePlus2 size={14} aria-hidden /> {t("Add new files")}
          </button>
          <input
            ref={newFileInput}
            type="file"
            multiple
            hidden
            aria-label={t("New files")}
            onChange={(e) => (e.target.files?.length ? void addNewFiles(e.target.files) : undefined, (e.target.value = ""))}
          />
          <MenuButton label={t("More")}>
            {(close) => (
              <>
                <a
                  role="menuitem"
                  className="menu-link"
                  href={`/api/v1/workspaces/${id}/zip`}
                  onClick={(e) => (e.preventDefault(), void downloadZip(id, workspace.name), close())}
                >
                  <Download size={14} aria-hidden /> {t("Download all as a zip")}
                </a>
                <button role="menuitem" onClick={() => (setEditing(true), close())}>
                  {t("Workspace settings")}
                </button>
                <hr />
                <button
                  role="menuitem"
                  onClick={() => (
                    close(),
                    window.confirm(t("Delete the workspace “{name}”? The objects stay as they are.", { name: workspace.name })) &&
                      remove.mutate()
                  )}
                >
                  <Trash2 size={14} aria-hidden /> {t("Delete workspace")}
                </button>
              </>
            )}
          </MenuButton>
        </div>
      </section>

      <div className="chip-row ws-chips" role="group" aria-label={t("File state")}>
        <button className={filter ? "chip" : "chip on"} aria-pressed={!filter} onClick={() => setFilter("")}>
          {t("All")} <span className="chip-count">{items.length}</span>
        </button>
        {(Object.keys(FILE_STATES()) as FileState[])
          .filter((s) => counts.get(s))
          .map((s) => (
            <button
              key={s}
              className={filter === s ? "chip on" : "chip"}
              aria-pressed={filter === s}
              onClick={() => setFilter(filter === s ? "" : s)}
            >
              <FileStateBadge state={s} /> <span className="chip-count">{counts.get(s)}</span>
            </button>
          ))}
      </div>

      <ErrorBanner error={failure ?? remove.error} />

      <Panel
        title={t("Files")}
        flush
        actions={
          <span className="row ws-actions">
            {busy && <span className="muted small">{busy}…</span>}
            <button className="btn small" disabled={!!busy} onClick={() => void update()} title={t("Download the newest files")}>
              <RefreshCw size={13} aria-hidden /> {selected.size ? t("Update") : t("Update all")}
            </button>
            <button
              className="btn small"
              disabled={!!busy || !selected.size}
              onClick={() => void op("/api/v1/workspaces/{id}/check-out", t("Checking out"))}
            >
              <LockOpen size={13} aria-hidden /> {t("Check out")}
            </button>
            <button
              className="btn small"
              disabled={!!busy || !selected.size}
              onClick={() => void op("/api/v1/workspaces/{id}/undo", t("Undoing"))}
            >
              <Undo2 size={13} aria-hidden /> {t("Undo check-out")}
            </button>
            <button className="btn small primary" disabled={!!busy || !mine.length} onClick={() => setCheckingIn(true)}>
              {t("Check in…")}
            </button>
            <button
              className="btn small ghost"
              disabled={!!busy || !selected.size}
              aria-label={t("Take out of the workspace")}
              title={t("Take out of the workspace")}
              onClick={() =>
                run(t("Removing"), () =>
                  unwrap(api.POST("/api/v1/workspaces/{id}/items/remove", { params: { path: { id } }, body: { ids } })),
                )
              }
            >
              <Trash2 size={13} aria-hidden />
            </button>
          </span>
        }
      >
        {!items.length ? (
          <EmptyState
            icon={<Search size={18} />}
            title={t("Nothing here yet")}
            hint={t("Add parts, documents or CAD models: a CAD model brings what it depends on and its drawings.")}
          />
        ) : (
          <table className="grid ws-table" aria-label={t("Workspace files")}>
            <thead>
              <tr>
                <th className="check-cell">
                  <input
                    type="checkbox"
                    aria-label={t("Select all")}
                    checked={allShown}
                    onChange={() => setSelected(allShown ? new Set() : new Set(shown.map((i) => i.objectId)))}
                  />
                </th>
                <th>{t("State")}</th>
                <th>{t("Object")}</th>
                <th>{t("Version")}</th>
                <th>{t("File")}</th>
                <th aria-label={t("Actions")} />
              </tr>
            </thead>
            <tbody>
              {shown.map((i) => (
                <tr key={i.objectId} className={selected.has(i.objectId) ? "selected" : undefined}>
                  <td className="check-cell">
                    <input
                      type="checkbox"
                      aria-label={t("Select {number}", { number: i.number })}
                      checked={selected.has(i.objectId)}
                      onChange={() => toggle(i.objectId)}
                    />
                  </td>
                  <td>
                    <FileStateBadge state={i.fileState} />
                    {i.fileState === "CHECKED_OUT_BY_OTHER" && i.checkedOutBy && (
                      <span className="muted small ws-by">
                        <UserRound size={11} aria-hidden /> <PrincipalName id={i.checkedOutBy} />
                      </span>
                    )}
                  </td>
                  <td className="ws-object">
                    <span className="ws-object-line">
                      {i.typeId && <TypeIcon typeId={i.typeId} />}
                      <Link to={`/objects/${i.objectId}`} className="num-link">
                        {i.number}
                      </Link>
                      {i.cadKind && <span className="tag ws-kind">{i.cadKind.toLowerCase()}</span>}
                    </span>
                    <span className="ws-object-name" title={i.name}>
                      {i.name}
                    </span>
                  </td>
                  <td className="nowrap">
                    {i.baseVersion && i.baseVersion !== i.version ? (
                      <span title={t("Your computer has {base}; the latest is {latest}", { base: i.baseVersion, latest: i.version ?? "" })}>
                        <span className="muted">{i.baseVersion}</span> → <span className="version">{i.version}</span>
                      </span>
                    ) : (
                      <span className="version">{i.version}</span>
                    )}{" "}
                    {i.state && <StateBadge state={i.state} />}
                  </td>
                  <td className="ws-file">
                    {(i.workingFile ?? i.file) ? (
                      <>
                        <span className="ellipsis">{(i.workingFile ?? i.file)!.fileName}</span>
                        <span className="muted small">{size((i.workingFile ?? i.file)!.size)}</span>
                      </>
                    ) : (
                      <span className="muted small">{t("No file")}</span>
                    )}
                  </td>
                  <td className="nowrap">
                    <UploadButton
                      item={i}
                      disabled={!!busy || i.fileState === "CHECKED_OUT_BY_OTHER" || i.fileState === "UNAVAILABLE"}
                      onFile={(f) => void uploadChange(i, f)}
                    />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Panel>

      {newFiles.length > 0 && <NewFiles workspaceId={id} files={newFiles} onDone={refresh} />}

      <WorkspaceHistory id={id} />

      {adding && <AddDialog workspaceId={id} onClose={() => (setAdding(false), refresh())} />}
      {checkingIn && (
        <CheckInDialog
          workspaceId={id}
          items={selected.size ? mine.filter((i) => selected.has(i.objectId)) : mine}
          onClose={() => (setCheckingIn(false), refresh(), setSelected(new Set()))}
        />
      )}
      {editing && <WorkspaceDialog ws={workspace} onClose={() => setEditing(false)} />}
    </div>
  );
}

async function downloadZip(id: string, name: string) {
  const { data } = await api.GET("/api/v1/workspaces/{id}/zip", { params: { path: { id } }, parseAs: "blob" });
  if (!data) return;
  const url = URL.createObjectURL(data as Blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = `${name}.zip`;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

/** Sends a changed file for an item: it is hashed, uploaded into the working copy, checked out if needed. */
function UploadButton({ item, disabled, onFile }: { item: Item; disabled: boolean; onFile: (file: File) => void }) {
  const input = useRef<HTMLInputElement>(null);
  return (
    <>
      <button
        className="btn small ghost icon"
        disabled={disabled}
        onClick={() => input.current?.click()}
        title={t("Upload the file you changed")}
        aria-label={t("Upload change")}
      >
        <Upload size={15} aria-hidden />
      </button>
      <input
        ref={input}
        type="file"
        hidden
        aria-label={t("Changed file for {number}", { number: item.number })}
        onChange={(e) => {
          const f = e.target.files?.[0];
          if (f) onFile(f);
          e.target.value = "";
        }}
      />
    </>
  );
}

function CheckInDialog({ workspaceId, items, onClose }: { workspaceId: string; items: Item[]; onClose: () => void }) {
  const [comment, setComment] = useState("");
  const checkIn = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/api/v1/workspaces/{id}/check-in", {
          params: { path: { id: workspaceId } },
          body: { ids: items.map((i) => i.objectId), comment: comment || null },
        }),
      ),
    onSuccess: onClose,
  });
  return (
    <Dialog
      open
      title={tn("Check in {n} object", "Check in {n} objects", items.length)}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            {t("Cancel")}
          </button>
          <button className="btn primary" disabled={checkIn.isPending} onClick={() => checkIn.mutate()}>
            {t("Check in")}
          </button>
        </>
      }
    >
      <ErrorBanner error={checkIn.error} />
      <p className="muted">{t("All of them, or none if one can't: the error names which.")}</p>
      <ul className="ws-checkin-list">
        {items.map((i) => (
          <li key={i.objectId}>
            <strong>{i.number}</strong> {i.name} <FileStateBadge state={i.fileState} />
          </li>
        ))}
      </ul>
      <div className="field">
        <label htmlFor="ws-comment">{t("Comment")}</label>
        <textarea id="ws-comment" rows={2} value={comment} onChange={(e) => setComment(e.target.value)} autoFocus />
      </div>
    </Dialog>
  );
}

function AddDialog({ workspaceId, onClose }: { workspaceId: string; onClose: () => void }) {
  const [text, setText] = useState("");
  const [chosen, setChosen] = useState<Map<string, string>>(new Map());
  const [dependencies, setDependencies] = useState(true);
  const { items: clipboard } = useClipboard();
  const { data } = useObjectSearch(text, 20);
  const hits = data?.pages.flat() ?? [];
  const add = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/api/v1/workspaces/{id}/items", {
          params: { path: { id: workspaceId } },
          body: { ids: [...chosen.keys()], dependencies },
        }),
      ),
    onSuccess: onClose,
  });
  const toggle = (id: string, label: string) => {
    const next = new Map(chosen);
    if (next.has(id)) next.delete(id);
    else next.set(id, label);
    setChosen(next);
  };
  return (
    <Dialog
      open
      wide
      title={t("Add objects")}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            {t("Cancel")}
          </button>
          <button className="btn primary" disabled={!chosen.size || add.isPending} onClick={() => add.mutate()}>
            {tn("Add {n}", "Add {n}", chosen.size)}
          </button>
        </>
      }
    >
      <ErrorBanner error={add.error} />
      <div className="field">
        <label htmlFor="ws-find">{t("Find by number or name")}</label>
        <input id="ws-find" type="search" value={text} onChange={(e) => setText(e.target.value)} autoFocus />
      </div>
      {clipboard.length > 0 && !text && (
        <>
          <h3 className="group-title">{t("On your clipboard")}</h3>
          <ul className="ws-pick">
            {clipboard.map((c) => (
              <li key={c.id}>
                <label className="check">
                  <input type="checkbox" checked={chosen.has(c.id)} onChange={() => toggle(c.id, c.number)} />
                  <strong>{c.number}</strong> {c.name} <span className="version">{c.version}</span>
                </label>
              </li>
            ))}
          </ul>
        </>
      )}
      {hits.length > 0 && (
        <ul className="ws-pick" aria-label={t("Search results")}>
          {hits.map((o) => (
            <li key={o.id}>
              <label className="check">
                <input type="checkbox" checked={chosen.has(o.id)} onChange={() => toggle(o.id, o.number)} />
                <TypeIcon typeId={o.typeId} /> <strong>{o.number}</strong> {o.name} <span className="version">{o.latest.version}</span>
                <StateBadge state={o.latest.state} />
              </label>
            </li>
          ))}
        </ul>
      )}
      <label className="check">
        <input type="checkbox" checked={dependencies} onChange={(e) => setDependencies(e.target.checked)} />
        {t("For CAD models, add what they depend on and their drawings")}
      </label>
    </Dialog>
  );
}

function NewFiles({ workspaceId, files, onDone }: { workspaceId: string; files: Schemas["NewFile"][]; onDone: () => void }) {
  const { data: types } = useTypes();
  const documentTypes = (types ?? []).filter((ty) => ty.baseClass === "DOCUMENT" && ty.instantiable);
  return (
    <Panel title={t("New files")} note={t("On your computer, not in the system yet: choose a type to make each an object.")} flush>
      <ul className="ws-newfiles">
        {files.map((f) => (
          <NewFileRow key={f.id} workspaceId={workspaceId} file={f} types={documentTypes} onDone={onDone} />
        ))}
      </ul>
    </Panel>
  );
}

function NewFileRow({
  workspaceId,
  file,
  types,
  onDone,
}: {
  workspaceId: string;
  file: Schemas["NewFile"];
  types: Schemas["TypeResponse"][];
  onDone: () => void;
}) {
  const cadFile = /\.(step|stp|fcstd|sldprt|sldasm|slddrw|prt|asm|ipt|iam|catpart|catproduct|kicad_pcb|kicad_sch)$/i.test(file.fileName);
  const guess = cadFile ? types.find((ty) => /cad/i.test(ty.displayName)) : types.find((ty) => !/cad/i.test(ty.displayName));
  const [chosenType, setTypeId] = useState("");
  const typeId = chosenType || guess?.id || types[0]?.id || "";
  const [name, setName] = useState(file.fileName.replace(/\.[^.]+$/, ""));
  const create = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/api/v1/workspaces/{id}/new-files/{fileId}/create", {
          params: { path: { id: workspaceId, fileId: file.id } },
          body: { typeId, name },
        }),
      ),
    onSuccess: onDone,
  });
  const drop = useMutation({
    mutationFn: () =>
      unwrap(api.DELETE("/api/v1/workspaces/{id}/new-files/{fileId}", { params: { path: { id: workspaceId, fileId: file.id } } })),
    onSuccess: onDone,
  });
  return (
    <li className="ws-newfile">
      <FilePlus2 size={15} className="icon-muted" aria-hidden />
      <span className="ws-newfile-name">
        <strong>{file.fileName}</strong>
        <span className="muted small">{size(file.size)}</span>
      </span>
      <select aria-label={t("Type for {file}", { file: file.fileName })} value={typeId} onChange={(e) => setTypeId(e.target.value)}>
        {types.map((ty) => (
          <option key={ty.id} value={ty.id}>
            {typeLabel(ty)}
          </option>
        ))}
      </select>
      <input aria-label={t("Name for {file}", { file: file.fileName })} value={name} onChange={(e) => setName(e.target.value)} />
      <button className="btn small primary" disabled={!typeId || create.isPending} onClick={() => create.mutate()}>
        {t("Make it an object")}
      </button>
      <button className="btn small ghost" aria-label={t("Forget {file}", { file: file.fileName })} onClick={() => drop.mutate()}>
        <Trash2 size={13} aria-hidden />
      </button>
      {create.error != null && (
        <div className="ws-newfile-error">
          <ErrorBanner error={create.error} />
        </div>
      )}
    </li>
  );
}

const EVENT_LABELS = (): Record<string, string> => ({
  CREATED: t("Workspace created"),
  ADDED: t("Added"),
  REMOVED: t("Taken out"),
  CHECKED_OUT: t("Checked out"),
  CHECKED_IN: t("Checked in"),
  UNDONE: t("Check-out undone"),
  UPLOADED: t("Change uploaded"),
  CREATED_OBJECT: t("New object from a file"),
});

function WorkspaceHistory({ id }: { id: string }) {
  const [open, setOpen] = useState(false);
  const { data } = useQuery({
    queryKey: ["workspaces", id, "history"],
    queryFn: () => unwrap(api.GET("/api/v1/workspaces/{id}/history", { params: { path: { id } } })),
    enabled: open,
  });
  return (
    <Panel
      title={t("History")}
      flush
      actions={
        <button className="btn small ghost" aria-expanded={open} onClick={() => setOpen(!open)}>
          <History size={13} aria-hidden /> {open ? t("Hide") : t("Show")}
        </button>
      }
    >
      {open &&
        (!data ? (
          <Loading />
        ) : (
          <ul className="mini-list ws-history">
            {data.map((e, i) => (
              <li key={i}>
                <span className="ws-history-kind">{EVENT_LABELS()[e.kind] ?? e.kind}</span>
                {e.objectId ? (
                  <Link to={`/objects/${e.objectId}`} className="num-link">
                    {e.number}
                  </Link>
                ) : null}
                <span className="grow muted small ellipsis">{e.detail}</span>
                <span className="muted small nowrap">
                  <When iso={e.at} />
                </span>
              </li>
            ))}
          </ul>
        ))}
    </Panel>
  );
}
