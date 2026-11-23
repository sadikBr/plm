import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { fetchPdf, usePublishSettings } from "../publishing/Publishing";
import { Download, Eye, File, FileImage, FileText, Paperclip, Star, Trash2, Upload } from "lucide-react";
import { useRef, useState, type DragEvent } from "react";
import { api, unwrap, type Schemas } from "../api/client";
import { t } from "../i18n/i18n";
import { Dialog, ErrorBanner, Loading, Panel, PrincipalName, formatDate } from "./common";

export type ContentItem = Schemas["ContentItem"];
type Role = ContentItem["role"];

const PREVIEWABLE_TYPES = /^(image\/(png|jpeg|gif|bmp|webp|svg\+xml)|application\/pdf|text\/.*|application\/json|application\/xml)$/;
/** Office files (roadmap 8.4): downloaded with the object's attributes written into their properties. */
const OFFICE = /\.(docx|docm|xlsx|xlsm|pptx|pptm|dotx|xltx|potx)$/i;
/** Shown in the browser: images, PDFs, text, and Office files once a PDF was made of them. */
/** A file type as people call it ("Word document"), else its media type. */
export function kindOf(contentType: string): string {
  const kinds: [RegExp, string][] = [
    [/wordprocessingml|msword/, t("Word document")],
    [/spreadsheetml|ms-excel/, t("Excel workbook")],
    [/presentationml|ms-powerpoint/, t("PowerPoint presentation")],
    [/^application\/pdf$/, "PDF"],
    [/^image\//, t("Image")],
    [/^text\/plain$/, t("Text")],
  ];
  return kinds.find(([re]) => re.test(contentType))?.[1] ?? contentType;
}
const previewable = (item: ContentItem) => PREVIEWABLE_TYPES.test(item.contentType) || item.previewPdf;

export function formatSize(bytes: number): string {
  if (bytes < 1024) return t("{size} B", { size: bytes });
  if (bytes < 1024 * 1024) return t("{size} KB", { size: (bytes / 1024).toFixed(1) });
  if (bytes < 1024 * 1024 * 1024) return t("{size} MB", { size: (bytes / 1024 / 1024).toFixed(1) });
  return t("{size} GB", { size: (bytes / 1024 / 1024 / 1024).toFixed(2) });
}

function FileIcon({ type, size = 16 }: { type: string; size?: number }) {
  if (type.startsWith("image/")) return <FileImage size={size} aria-hidden />;
  if (type.startsWith("text/") || type === "application/pdf") return <FileText size={size} aria-hidden />;
  return <File size={size} aria-hidden />;
}

/** The files of an iteration: the latest's, or the actor's working copy's. */
export function useContent(objectId: string, iterationKey: string, workingCopy: boolean, iterationId?: string) {
  return useQuery({
    queryKey: ["content", objectId, iterationKey, workingCopy, iterationId ?? ""],
    queryFn: () =>
      unwrap(
        api.GET("/api/v1/objects/{id}/content", {
          params: { path: { id: objectId }, query: { workingCopy, iterationId } },
        }),
      ),
    // Thumbnails are made in the background: look again while one is coming.
    refetchInterval: (q) => (q.state.data?.some((c) => c.thumbnail === "PENDING") ? 1500 : false),
  });
}

export async function fileLink(contentId: string, inline: boolean): Promise<string> {
  const link = await unwrap(api.GET("/api/v1/content/{contentId}/link", { params: { path: { contentId }, query: { inline } } }));
  return link.url;
}

/** Sends a file to the vault (with progress): its upload id, to attach somewhere. */
export async function sendToVault(file: File, onProgress: (fraction: number) => void = () => {}): Promise<string> {
  const ticket = await unwrap(
    api.POST("/api/v1/vault/uploads", { body: { fileName: file.name, contentType: file.type || null, size: file.size } }),
  );
  await new Promise<void>((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open("PUT", ticket.uploadUrl);
    xhr.upload.onprogress = (e) => e.lengthComputable && onProgress(e.loaded / e.total);
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) resolve();
      else {
        let detail = t("The upload failed ({status})", { status: xhr.status });
        try {
          detail = (JSON.parse(xhr.responseText) as { detail?: string }).detail ?? detail;
        } catch {
          /* not JSON */
        }
        reject(new Error(detail));
      }
    };
    xhr.onerror = () => reject(new Error(t("The upload failed: the connection was lost")));
    xhr.send(file);
  });
  return ticket.uploadId;
}

/** Sends a file to the vault (with progress) and attaches it to the working copy. */
export async function uploadFile(
  objectId: string,
  file: File,
  role: Role,
  onProgress: (fraction: number) => void,
  description?: string,
): Promise<ContentItem> {
  const uploadId = await sendToVault(file, onProgress);
  return unwrap(
    api.POST("/api/v1/objects/{id}/content", {
      params: { path: { id: objectId } },
      body: { uploadId, role, description: description ?? null },
    }),
  );
}

function Thumbnail({ item, size = 48 }: { item: ContentItem; size?: number }) {
  const { data: url } = useQuery({
    queryKey: ["thumbnail", item.id],
    queryFn: async () => (await unwrap(api.GET("/api/v1/content/{contentId}/thumbnail", { params: { path: { contentId: item.id } } }))).url,
    enabled: item.thumbnail === "READY",
    staleTime: 5 * 60_000,
  });
  return (
    <span className="file-thumb" style={{ width: size, height: size }}>
      {url ? <img src={url} alt="" /> : <FileIcon type={item.contentType} size={Math.round(size / 2.2)} />}
    </span>
  );
}

/**
 * An object's files (roadmap 3.2): its primary file and attachments. With the object checked out
 * to you, the working copy's, which you can change: they become the object's at check-in.
 */
export function ObjectFiles({ objectId, iterationKey, editable }: { objectId: string; iterationKey: string; editable: boolean }) {
  const qc = useQueryClient();
  const { data: items, isLoading, error } = useContent(objectId, iterationKey, editable);
  const [preview, setPreview] = useState<ContentItem | null>(null);
  const [uploads, setUploads] = useState<{ name: string; progress: number }[]>([]);
  const [uploadError, setUploadError] = useState<unknown>(null);
  const [dragging, setDragging] = useState(false);
  const primaryInput = useRef<HTMLInputElement>(null);
  const attachmentInput = useRef<HTMLInputElement>(null);
  const refresh = () => qc.invalidateQueries({ queryKey: ["content", objectId] });
  const remove = useMutation({
    mutationFn: (contentId: string) =>
      unwrap(api.DELETE("/api/v1/objects/{id}/content/{contentId}", { params: { path: { id: objectId, contentId } } })),
    onSuccess: refresh,
  });

  const send = async (files: FileList | File[], role: Role) => {
    setUploadError(null);
    for (const [i, file] of [...files].entries()) {
      const r: Role = role === "PRIMARY" && i === 0 ? "PRIMARY" : "ATTACHMENT";
      setUploads((u) => [...u, { name: file.name, progress: 0 }]);
      try {
        await uploadFile(objectId, file, r, (p) => setUploads((u) => u.map((x) => (x.name === file.name ? { ...x, progress: p } : x))));
      } catch (e) {
        setUploadError(e);
      } finally {
        setUploads((u) => u.filter((x) => x.name !== file.name));
        void refresh();
      }
    }
  };

  const { data: watermarks } = usePublishSettings();
  const download = async (item: ContentItem) => {
    // PDFs carry who downloaded them, when the publishing settings say so (roadmap 6.7).
    if (watermarks?.watermarkDownloads && /\.pdf$/i.test(item.fileName) && item.iterationId) {
      return fetchPdf(`/api/v1/content/${item.id}/watermarked`, item.fileName);
    }
    if (OFFICE.test(item.fileName)) return fetchPdf(`/api/v1/office/content/${item.id}`, item.fileName);
    const a = document.createElement("a");
    a.href = await fileLink(item.id, false);
    a.download = item.fileName;
    a.click();
  };

  const primary = items?.find((c) => c.role === "PRIMARY");
  const attachments = items?.filter((c) => c.role === "ATTACHMENT") ?? [];
  const drop = (e: DragEvent) => {
    e.preventDefault();
    setDragging(false);
    if (editable && e.dataTransfer.files.length) void send(e.dataTransfer.files, primary ? "ATTACHMENT" : "PRIMARY");
  };

  const row = (item: ContentItem) => (
    <tr key={item.id}>
      <td>
        <div className="file-name">
          <Thumbnail item={item} size={36} />
          <div>
            <button className="link-btn" onClick={() => (previewable(item) ? setPreview(item) : void download(item))}>
              {item.fileName}
            </button>
            {item.description && <div className="muted small">{item.description}</div>}
          </div>
        </div>
      </td>
      <td className="nowrap muted">{formatSize(item.size)}</td>
      <td className="nowrap muted">{kindOf(item.contentType)}</td>
      <td className="nowrap muted">
        {formatDate(item.addedAt)} · <PrincipalName id={item.addedBy} />
      </td>
      <td className="nowrap">
        {previewable(item) && (
          <button
            className="icon-btn"
            aria-label={t("Preview {name}", { name: item.fileName })}
            title={t("Preview")}
            onClick={() => setPreview(item)}
          >
            <Eye size={14} />
          </button>
        )}
        <button
          className="icon-btn"
          aria-label={t("Download {name}", { name: item.fileName })}
          title={t("Download")}
          onClick={() => void download(item)}
        >
          <Download size={14} />
        </button>
        {editable && (
          <button
            className="icon-btn"
            aria-label={t("Remove {name}", { name: item.fileName })}
            title={t("Remove from the working copy")}
            disabled={remove.isPending}
            onClick={() => remove.mutate(item.id)}
          >
            <Trash2 size={14} />
          </button>
        )}
      </td>
    </tr>
  );

  return (
    <Panel
      title={t("Files")}
      note={editable ? t("Your working copy's files: they become the object's when you check in") : undefined}
      flush
      actions={
        editable ? (
          <>
            <input
              ref={primaryInput}
              type="file"
              hidden
              aria-label={t("Primary file")}
              onChange={(e) => e.target.files && void send(e.target.files, "PRIMARY")}
            />
            <input
              ref={attachmentInput}
              type="file"
              multiple
              hidden
              aria-label={t("Attachments")}
              onChange={(e) => e.target.files && void send(e.target.files, "ATTACHMENT")}
            />
            <button className="btn small" onClick={() => primaryInput.current?.click()}>
              <Star size={13} aria-hidden />
              {primary ? t("Replace primary file") : t("Add primary file")}
            </button>
            <button className="btn small" onClick={() => attachmentInput.current?.click()}>
              <Paperclip size={13} aria-hidden />
              {t("Attach files")}
            </button>
          </>
        ) : undefined
      }
    >
      <div
        className={`files ${dragging ? "dragging" : ""}`}
        onDragOver={(e) => {
          if (!editable) return;
          e.preventDefault();
          setDragging(true);
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={drop}
      >
        <ErrorBanner error={error ?? uploadError ?? remove.error} />
        {uploads.map((u) => (
          <div key={u.name} className="upload-row" role="status">
            <Upload size={13} aria-hidden /> {u.name}
            <div className="job-progress">
              <div className="bar">
                <span style={{ width: `${Math.round(u.progress * 100)}%` }} />
              </div>
            </div>
          </div>
        ))}
        {isLoading ? (
          <Loading />
        ) : (
          <>
            {primary ? (
              <div className="primary-file">
                <Thumbnail item={primary} size={96} />
                <div style={{ display: "grid", gap: 4, minWidth: 0 }}>
                  <span className="muted small">{t("Primary file")}</span>
                  <strong className="wrap-code" style={{ fontFamily: "inherit", fontSize: 14 }}>
                    {primary.fileName}
                  </strong>
                  <span className="muted small">
                    {formatSize(primary.size)} · {kindOf(primary.contentType)}
                    {primary.description ? ` · ${primary.description}` : ""}
                  </span>
                  <div className="row" style={{ gap: 6 }}>
                    {previewable(primary) && (
                      <button className="btn small" onClick={() => setPreview(primary)}>
                        <Eye size={13} aria-hidden /> {t("Preview")}
                      </button>
                    )}
                    <button className="btn small" onClick={() => void download(primary)}>
                      <Download size={13} aria-hidden /> {t("Download")}
                    </button>
                    {editable && (
                      <button className="btn small ghost" onClick={() => remove.mutate(primary.id)} disabled={remove.isPending}>
                        <Trash2 size={13} aria-hidden /> {t("Remove")}
                      </button>
                    )}
                  </div>
                </div>
              </div>
            ) : (
              <p className="empty">
                {t("No primary file.")} {editable ? t("Drop files here, or use the buttons above.") : t("Check the object out to add one.")}
              </p>
            )}
            {attachments.length > 0 && (
              <table className="grid" aria-label={t("Attachments")}>
                <thead>
                  <tr>
                    <th>{t("Attachment")}</th>
                    <th>{t("Size")}</th>
                    <th>{t("Type")}</th>
                    <th>{t("Added")}</th>
                    <th aria-label={t("Actions")} />
                  </tr>
                </thead>
                <tbody>{attachments.map(row)}</tbody>
              </table>
            )}
          </>
        )}
      </div>
      {preview && <PreviewDialog item={preview} onClose={() => setPreview(null)} onDownload={() => void download(preview)} />}
    </Panel>
  );
}

/** Shows a file in the page: images and PDFs as they are, text as text. */
export function PreviewDialog({ item, onClose, onDownload }: { item: ContentItem; onClose: () => void; onDownload: () => void }) {
  const { data: url, error } = useQuery({
    queryKey: ["fileLink", item.id, "inline"],
    queryFn: () => fileLink(item.id, true),
    staleTime: 5 * 60_000,
  });
  const isText = item.contentType.startsWith("text/") || item.contentType === "application/json" || item.contentType === "application/xml";
  const { data: text } = useQuery({
    queryKey: ["fileText", item.id],
    queryFn: async () => (await fetch(url!)).text(),
    enabled: !!url && isText,
  });
  return (
    <Dialog
      open
      wide
      title={item.fileName}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onDownload}>
            <Download size={14} aria-hidden /> {t("Download")}
          </button>
          <button className="btn primary" onClick={onClose}>
            {t("Close")}
          </button>
        </>
      }
    >
      <ErrorBanner error={error} />
      <div className="file-preview">
        {!url ? (
          <Loading />
        ) : item.contentType.startsWith("image/") ? (
          <img src={url} alt={item.fileName} />
        ) : item.contentType === "application/pdf" || item.previewPdf ? (
          <PdfPages item={item} inlineUrl={url} />
        ) : (
          <pre>{text?.slice(0, 200_000) ?? ""}</pre>
        )}
      </div>
    </Dialog>
  );
}

/** A PDF, page by page, as images the server renders (every browser shows them, viewer or not). */
function PdfPages({ item, inlineUrl }: { item: ContentItem; inlineUrl: string }) {
  const [page, setPage] = useState(1);
  const { data, error, isFetching } = useQuery({
    queryKey: ["pdfPage", item.id, page],
    queryFn: () => unwrap(api.GET("/api/v1/content/{contentId}/pages/{page}", { params: { path: { contentId: item.id, page } } })),
    staleTime: 5 * 60_000,
  });
  return (
    <div className="pdf-pages">
      <div className="row" style={{ gap: 8, justifyContent: "center" }}>
        <button className="btn small" disabled={page <= 1} onClick={() => setPage(page - 1)}>
          {t("Previous")}
        </button>
        <span className="muted small" aria-live="polite">
          {data ? t("Page {page} of {pages}", { page, pages: data.pages }) : t("Page {page}", { page })}
        </span>
        <button className="btn small" disabled={!data || page >= data.pages} onClick={() => setPage(page + 1)}>
          {t("Next")}
        </button>
        <a className="btn small ghost" href={inlineUrl} target="_blank" rel="noreferrer">
          {t("Open in a new tab")}
        </a>
      </div>
      <ErrorBanner error={error} />
      {data ? (
        <img src={data.url} alt={t("{name}, page {page}", { name: item.fileName, page })} style={{ opacity: isFetching ? 0.6 : 1 }} />
      ) : (
        <Loading />
      )}
    </div>
  );
}
