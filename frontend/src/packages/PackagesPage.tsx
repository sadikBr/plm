import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Ban, Copy, Package, Plus, Send, Trash2 } from "lucide-react";
import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { api, unwrap, type Schemas } from "../api/client";
import { useObjectSearch } from "../api/queries";
import { config } from "../config";
import { Dialog, EmptyState, ErrorBanner, Loading, Panel, StateBadge, TypeIcon, When } from "../components/common";
import { PageHeader } from "../components/Layout";
import { t, tn } from "../i18n/i18n";

type Pkg = Schemas["DataPackage"];
type Contents = Schemas["PackageContents"];

const REASONS = (): Record<string, string> => ({ ROOT: t("Chosen"), STRUCTURE: t("In the structure"), DOCUMENT: t("Describes a part") });

/** Packages and deliveries (roadmap 9.4): sets of objects to send outside, and who got them. */
export function PackagesPage() {
    const { id } = useParams();
    const navigate = useNavigate();
    const { data: list, isLoading } = useQuery({
        queryKey: ["packages"],
        queryFn: () => unwrap(api.GET("/api/v1/packages")),
        // While a delivery is being prepared.
        refetchInterval: (q) => (q.state.data?.some((p) => p.deliveries.some((d) => d.status === "PREPARING")) ? 1500 : false),
    });
    const [creating, setCreating] = useState(false);
    const current = list?.find((p) => p.id === id) ?? list?.[0];
    return (
        <div className="page">
            <PageHeader
                icon={<Package size={18} className="icon-muted" aria-hidden />}
                title={<span className="nm">{t("Packages")}</span>}
                meta={<span className="sub">{t("Sets of objects to send to suppliers and partners, with what each recipient got.")}</span>}
                toolbar={
                    <button className="btn small primary" onClick={() => setCreating(true)}>
                        <Plus size={14} aria-hidden /> {t("New package")}
                    </button>
                }
            />
            {isLoading ? (
                <Loading />
            ) : !list?.length ? (
                <div className="panel">
                    <EmptyState
                        icon={<Package size={20} aria-hidden />}
                        title={t("No packages yet")}
                        hint={t("Choose the objects to send; their structure, documents, files and PDFs can come along.")}
                    />
                </div>
            ) : (
                <div className="admin-split">
                    <Panel title={t("Packages")} flush>
                        <ul className="type-tree">
                            {list.map((p) => (
                                <li key={p.id}>
                                    <button
                                        className={current?.id === p.id ? "type-row active" : "type-row"}
                                        onClick={() => navigate(`/packages/${p.id}`)}
                                    >
                                        {p.name}
                                        <span className="muted" style={{ marginLeft: "auto" }}>
                                            {p.deliveries.length}
                                        </span>
                                    </button>
                                </li>
                            ))}
                        </ul>
                    </Panel>
                    {current && <PackageDetails key={current.id} pkg={current} />}
                </div>
            )}
            {creating && <PackageDialog onClose={() => setCreating(false)} onSaved={(p) => navigate(`/packages/${p.id}`)} />}
        </div>
    );
}

function PackageDetails({ pkg }: { pkg: Pkg }) {
    const qc = useQueryClient();
    const navigate = useNavigate();
    const [delivering, setDelivering] = useState(false);
    const [editing, setEditing] = useState(false);
    const members = useQuery({
        queryKey: ["packages", pkg.id, "members", pkg.updatedAt],
        queryFn: () => unwrap(api.GET("/api/v1/packages/{id}/members", { params: { path: { id: pkg.id } } })),
    });
    const remove = useMutation({
        mutationFn: () => unwrap(api.DELETE("/api/v1/packages/{id}", { params: { path: { id: pkg.id } } })),
        onSuccess: () => {
            void qc.invalidateQueries({ queryKey: ["packages"] });
            navigate("/packages");
        },
    });
    const revoke = useMutation({
        mutationFn: (id: string) => unwrap(api.POST("/api/v1/deliveries/{id}/revoke", { params: { path: { id } } })),
        onSuccess: () => qc.invalidateQueries({ queryKey: ["packages"] }),
    });
    const includes = [
        pkg.contents.structure && t("structure"),
        pkg.contents.documents && t("documents"),
        pkg.contents.files && t("files"),
        pkg.contents.pdfs && t("PDFs"),
    ].filter(Boolean);
    return (
        <div className="stack">
            <Panel
                title={pkg.name}
                note={`${pkg.spec === "RELEASED" ? t("Released versions") : t("Latest versions")} · ${t("with {what}", { what: includes.join(", ") })}`}
                actions={
                    <>
                        <button className="btn small" onClick={() => setEditing(true)}>
                            {t("Edit")}
                        </button>
                        <button className="btn small primary" onClick={() => setDelivering(true)}>
                            <Send size={13} aria-hidden /> {t("Deliver…")}
                        </button>
                        <button className="icon-btn" aria-label={t("Delete the package")} onClick={() => remove.mutate()}>
                            <Trash2 size={14} />
                        </button>
                    </>
                }
                flush
            >
                {pkg.description && <p className="muted pkg-description">{pkg.description}</p>}
                <ErrorBanner error={members.error ?? remove.error} />
                {members.isLoading ? (
                    <Loading />
                ) : (
                    <table className="grid" aria-label={t("Contents")}>
                        <thead>
                            <tr>
                                <th>{t("Number")}</th>
                                <th>{t("Name")}</th>
                                <th>{t("Version")}</th>
                                <th>{t("Why")}</th>
                                <th className="num">{t("Files")}</th>
                            </tr>
                        </thead>
                        <tbody>
                            {members.data?.map((m) => (
                                <tr key={m.objectId}>
                                    <td className="nowrap">
                                        <Link to={`/objects/${m.objectId}`}>{m.number}</Link>
                                    </td>
                                    <td>
                                        {m.name} <span className="muted small">{m.type}</span>
                                    </td>
                                    <td className="nowrap">
                                        {m.version} <StateBadge state={m.state} />
                                    </td>
                                    <td className="small">{REASONS()[m.reason] ?? m.reason}</td>
                                    <td className="num">{m.files || ""}</td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                )}
            </Panel>
            <Panel title={t("Deliveries")} note={tn("{n} delivery", "{n} deliveries", pkg.deliveries.length)} flush>
                <ErrorBanner error={revoke.error} />
                {pkg.deliveries.length === 0 ? (
                    <p className="empty">{t("Not delivered yet.")}</p>
                ) : (
                    <table className="grid" aria-label={t("Deliveries")}>
                        <thead>
                            <tr>
                                <th>{t("Recipient")}</th>
                                <th>{t("Status")}</th>
                                <th>{t("Expires")}</th>
                                <th className="num">{t("Downloads")}</th>
                                <th>{t("Sent")}</th>
                                <th aria-label={t("Actions")} />
                            </tr>
                        </thead>
                        <tbody>
                            {pkg.deliveries.map((d) => {
                                const url = `${config.apiBase || window.location.origin}${d.downloadPath}`;
                                const status = d.expired ? "EXPIRED" : d.status;
                                return (
                                    <tr key={d.id}>
                                        <td>
                                            <strong>{d.recipient}</strong>
                                            {d.email && <div className="muted small">{d.email}</div>}
                                        </td>
                                        <td>
                                            <span
                                                className={
                                                    status === "READY" ? "tag allow" : status === "PREPARING" ? "tag accent" : "tag deny"
                                                }
                                            >
                                                {{
                                                    READY: t("ready"),
                                                    PREPARING: t("preparing"),
                                                    FAILED: t("failed"),
                                                    REVOKED: t("withdrawn"),
                                                    EXPIRED: t("expired"),
                                                }[status] ?? status}
                                            </span>
                                            {d.size != null && <span className="muted small"> {(d.size / 1024).toFixed(0)} KB</span>}
                                            {d.error && <div className="small load-error">{d.error}</div>}
                                        </td>
                                        <td className="small">
                                            <When iso={d.expiresAt} />
                                        </td>
                                        <td className="num">
                                            {d.downloads}
                                            {d.lastDownloadAt && (
                                                <div className="muted small">
                                                    <When iso={d.lastDownloadAt} />
                                                </div>
                                            )}
                                        </td>
                                        <td className="small">
                                            <When iso={d.createdAt} />
                                        </td>
                                        <td className="nowrap">
                                            {status === "READY" && (
                                                <>
                                                    <button
                                                        className="btn small"
                                                        onClick={() => navigator.clipboard?.writeText(url)}
                                                        title={url}
                                                    >
                                                        <Copy size={12} aria-hidden /> {t("Copy link")}
                                                    </button>{" "}
                                                    <button className="btn small danger" onClick={() => revoke.mutate(d.id)}>
                                                        <Ban size={12} aria-hidden /> {t("Withdraw")}
                                                    </button>
                                                </>
                                            )}
                                        </td>
                                    </tr>
                                );
                            })}
                        </tbody>
                    </table>
                )}
            </Panel>
            {delivering && <DeliverDialog pkg={pkg} onClose={() => setDelivering(false)} />}
            {editing && <PackageDialog pkg={pkg} onClose={() => setEditing(false)} onSaved={() => setEditing(false)} />}
        </div>
    );
}

function DeliverDialog({ pkg, onClose }: { pkg: Pkg; onClose: () => void }) {
    const qc = useQueryClient();
    const [recipient, setRecipient] = useState("");
    const [email, setEmail] = useState("");
    const [message, setMessage] = useState("");
    const [days, setDays] = useState(14);
    const send = useMutation({
        mutationFn: () =>
            unwrap(
                api.POST("/api/v1/packages/{id}/deliveries", {
                    params: { path: { id: pkg.id } },
                    body: { recipient, email: email || null, message: message || null, days },
                }),
            ),
        onSuccess: () => {
            void qc.invalidateQueries({ queryKey: ["packages"] });
            onClose();
        },
    });
    return (
        <Dialog
            open
            title={t("Deliver {name}", { name: pkg.name })}
            onClose={onClose}
            footer={
                <>
                    <button className="btn" onClick={onClose}>
                        {t("Cancel")}
                    </button>
                    <button className="btn primary" disabled={!recipient.trim() || send.isPending} onClick={() => send.mutate()}>
                        <Send size={14} aria-hidden /> {t("Prepare the download")}
                    </button>
                </>
            }
        >
            <ErrorBanner error={send.error} />
            <div className="field">
                <label htmlFor="d-recipient">{t("Recipient")}</label>
                <input
                    id="d-recipient"
                    value={recipient}
                    onChange={(e) => setRecipient(e.target.value)}
                    placeholder={t("e.g. Acme Pumps, quality team")}
                />
            </div>
            <div className="field">
                <label htmlFor="d-email">{t("Email (optional)")}</label>
                <input id="d-email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} />
            </div>
            <div className="field">
                <label htmlFor="d-message">{t("Message (optional)")}</label>
                <textarea id="d-message" rows={2} value={message} onChange={(e) => setMessage(e.target.value)} />
            </div>
            <div className="field">
                <label htmlFor="d-days">{t("The link works for")}</label>
                <select id="d-days" value={days} onChange={(e) => setDays(Number(e.target.value))}>
                    {[3, 7, 14, 30, 90].map((n) => (
                        <option key={n} value={n}>
                            {tn("{n} day", "{n} days", n)}
                        </option>
                    ))}
                </select>
            </div>
            <p className="muted small">
                {t("The recipient needs no account. Each download is logged; you can withdraw the link at any time.")}
            </p>
        </Dialog>
    );
}

function PackageDialog({ pkg, onClose, onSaved }: { pkg?: Pkg; onClose: () => void; onSaved: (p: Pkg) => void }) {
    const qc = useQueryClient();
    const [name, setName] = useState(pkg?.name ?? "");
    const [description, setDescription] = useState(pkg?.description ?? "");
    const [spec, setSpec] = useState(pkg?.spec ?? "LATEST");
    const [contents, setContents] = useState<Contents>(pkg?.contents ?? { files: true, pdfs: false, structure: true, documents: true });
    const [roots, setRoots] = useState<Map<string, string>>(new Map((pkg?.rootIds ?? []).map((id) => [id, ""])));
    const [text, setText] = useState("");
    const { data } = useObjectSearch(text, 15);
    const hits = data?.pages.flat() ?? [];
    const save = useMutation({
        mutationFn: () => {
            const body = { name, description: description || null, rootIds: [...roots.keys()], spec, contents };
            return pkg
                ? unwrap(api.PUT("/api/v1/packages/{id}", { params: { path: { id: pkg.id } }, body }))
                : unwrap(api.POST("/api/v1/packages", { body }));
        },
        onSuccess: (p) => {
            void qc.invalidateQueries({ queryKey: ["packages"] });
            onSaved(p);
            onClose();
        },
    });
    const toggle = (id: string, label: string) => {
        const next = new Map(roots);
        if (next.has(id)) next.delete(id);
        else next.set(id, label);
        setRoots(next);
    };
    const flag = (k: keyof Contents, label: string) => (
        <label className="check">
            <input type="checkbox" checked={contents[k]} onChange={(e) => setContents({ ...contents, [k]: e.target.checked })} />
            {label}
        </label>
    );
    return (
        <Dialog
            open
            wide
            title={pkg ? t("Edit the package") : t("New package")}
            onClose={onClose}
            footer={
                <>
                    <button className="btn" onClick={onClose}>
                        {t("Cancel")}
                    </button>
                    <button
                        className="btn primary"
                        disabled={!name.trim() || roots.size === 0 || save.isPending}
                        onClick={() => save.mutate()}
                    >
                        {t("Save")}
                    </button>
                </>
            }
        >
            <ErrorBanner error={save.error} />
            <div className="pkg-form">
                <div className="field">
                    <label htmlFor="p-name">{t("Name")}</label>
                    <input
                        id="p-name"
                        value={name}
                        onChange={(e) => setName(e.target.value)}
                        placeholder={t("e.g. Battery tray RFQ for Acme")}
                    />
                </div>
                <div className="field">
                    <label htmlFor="p-spec">{t("Versions")}</label>
                    <select id="p-spec" value={spec} onChange={(e) => setSpec(e.target.value)}>
                        <option value="LATEST">{t("Latest")}</option>
                        <option value="RELEASED">{t("Latest released")}</option>
                    </select>
                </div>
            </div>
            <div className="field">
                <label htmlFor="p-description">{t("Description")}</label>
                <input id="p-description" value={description} onChange={(e) => setDescription(e.target.value)} />
            </div>
            <div className="pkg-flags">
                {flag("structure", t("Their structure"))}
                {flag("documents", t("Documents that describe the parts"))}
                {flag("files", t("Files"))}
                {flag("pdfs", t("PDFs made from the files"))}
            </div>
            <div className="field">
                <label htmlFor="p-find">
                    {tn("{n} object chosen: find more by number or name", "{n} objects chosen: find more by number or name", roots.size)}
                </label>
                <input id="p-find" type="search" value={text} onChange={(e) => setText(e.target.value)} />
            </div>
            {hits.length > 0 && (
                <ul className="ws-pick" aria-label={t("Search results")}>
                    {hits.map((o) => (
                        <li key={o.id}>
                            <label className="check">
                                <input type="checkbox" checked={roots.has(o.id)} onChange={() => toggle(o.id, o.number)} />
                                <TypeIcon typeId={o.typeId} /> <strong>{o.number}</strong> {o.name}{" "}
                                <span className="version">{o.latest.version}</span>
                                <StateBadge state={o.latest.state} />
                            </label>
                        </li>
                    ))}
                </ul>
            )}
        </Dialog>
    );
}
