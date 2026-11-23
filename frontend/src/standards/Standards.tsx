import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Box, ExternalLink as LinkIcon, Plus, X } from "lucide-react";
import { useState } from "react";
import { useParams, useSearchParams } from "react-router";
import { api, unwrap } from "../api/client";
import {
    ErrorBanner,
    Loading,
    Panel,
    StateBadge,
    TypeIcon,
} from "../components/common";
import { fetchPdf } from "../publishing/Publishing";
import { t } from "../i18n/i18n";

/** Downloads the structure as STEP AP242, with the CAD files it references (roadmap 9.6). */
export function StepExportButton({
    objectId,
    released,
}: {
    objectId: string;
    released: boolean;
}) {
    const [busy, setBusy] = useState(false);
    return (
        <button
            className="btn small"
            disabled={busy}
            title={t(
                "The structure as STEP AP242, with the CAD files of its documents",
            )}
            onClick={() => {
                setBusy(true);
                void fetchPdf(
                    `/api/v1/objects/${objectId}/step-export?released=${released}`,
                    "structure-step.zip",
                ).finally(() => setBusy(false));
            }}
        >
            <Box size={13} aria-hidden /> {t("STEP AP242")}
        </button>
    );
}

/** Links to requirements, tests and changes in other tools (OSLC link types). */
export function ExternalLinksPanel({
    objectId,
    editable,
}: {
    objectId: string;
    editable: boolean;
}) {
    const qc = useQueryClient();
    const key = ["externalLinks", objectId];
    const { data, isLoading } = useQuery({
        queryKey: key,
        queryFn: () =>
            unwrap(
                api.GET("/api/v1/objects/{id}/external-links", {
                    params: { path: { id: objectId } },
                }),
            ),
    });
    const { data: types } = useQuery({
        queryKey: ["externalLinkTypes"],
        queryFn: () => unwrap(api.GET("/api/v1/external-link-types")),
    });
    const [adding, setAdding] = useState(false);
    const [linkType, setLinkType] = useState("oslc_rm:implementsRequirement");
    const [url, setUrl] = useState("");
    const [title, setTitle] = useState("");
    const add = useMutation({
        mutationFn: () =>
            unwrap(
                api.POST("/api/v1/objects/{id}/external-links", {
                    params: { path: { id: objectId } },
                    body: { linkType, url, title: title || null },
                }),
            ),
        onSuccess: () => {
            setUrl("");
            setTitle("");
            setAdding(false);
            void qc.invalidateQueries({ queryKey: key });
        },
    });
    const remove = useMutation({
        mutationFn: (linkId: string) =>
            unwrap(
                api.DELETE("/api/v1/objects/{id}/external-links/{linkId}", {
                    params: { path: { id: objectId, linkId } },
                }),
            ),
        onSuccess: () => qc.invalidateQueries({ queryKey: key }),
    });
    const label = (k: string) => types?.find((x) => x.key === k)?.label ?? k;
    if (isLoading) return null;
    if (!data?.length && !editable) return null;
    return (
        <Panel
            title={t("Links to other tools")}
            note={t("Requirements, tests and changes elsewhere (OSLC)")}
            actions={
                editable && !adding ? (
                    <button
                        className="btn small"
                        onClick={() => setAdding(true)}
                    >
                        <Plus size={13} aria-hidden /> {t("Link")}
                    </button>
                ) : undefined
            }
            flush
        >
            <ErrorBanner error={add.error ?? remove.error} />
            {data?.length ? (
                <ul className="ext-links">
                    {data.map((l) => (
                        <li key={l.id}>
                            <span className="tag">{t(label(l.linkType))}</span>
                            <a href={l.url} target="_blank" rel="noreferrer">
                                {l.title ?? l.url}{" "}
                                <LinkIcon size={12} aria-hidden />
                            </a>
                            {editable && (
                                <button
                                    className="icon-btn"
                                    aria-label={t("Remove")}
                                    onClick={() => remove.mutate(l.id)}
                                >
                                    <X size={13} />
                                </button>
                            )}
                        </li>
                    ))}
                </ul>
            ) : (
                !adding && <p className="empty">{t("No links yet.")}</p>
            )}
            {adding && (
                <form
                    className="ext-link-form"
                    onSubmit={(e) => {
                        e.preventDefault();
                        add.mutate();
                    }}
                >
                    <select
                        aria-label={t("Link type")}
                        value={linkType}
                        onChange={(e) => setLinkType(e.target.value)}
                    >
                        {types?.map((x) => (
                            <option key={x.key} value={x.key}>
                                {t(x.label)}
                            </option>
                        ))}
                    </select>
                    <input
                        aria-label={t("Address")}
                        placeholder="https://…"
                        value={url}
                        onChange={(e) => setUrl(e.target.value)}
                    />
                    <input
                        aria-label={t("Title")}
                        placeholder={t("Title, e.g. REQ-118 Shift time")}
                        value={title}
                        onChange={(e) => setTitle(e.target.value)}
                    />
                    <button
                        className="btn small primary"
                        disabled={!url.trim() || add.isPending}
                    >
                        {t("Add")}
                    </button>
                    <button
                        type="button"
                        className="btn small"
                        onClick={() => setAdding(false)}
                    >
                        {t("Cancel")}
                    </button>
                </form>
            )}
        </Panel>
    );
}

/**
 * OSLC delegated selection dialog (roadmap 9.6): another tool shows this page in a frame and gets
 * the chosen resources back by postMessage, as `oslc-response:{"oslc:results": [...]}`.
 */
export function OslcSelectPage() {
    const [params] = useSearchParams();
    const domain = params.get("domain") ?? "am";
    const [text, setText] = useState("");
    const contextId = params.get("contextId") ?? undefined;
    // Searched within the provider's product (the server filters, so its hits aren't crowded out).
    const { data: hits = [], isLoading } = useQuery({
        queryKey: ["oslcSelect", contextId, text],
        queryFn: () =>
            unwrap(
                api.GET("/api/v1/objects", {
                    params: {
                        query: {
                            q: text,
                            limit: 30,
                            ...(contextId ? { contextId } : {}),
                        },
                    },
                }),
            ),
        enabled: text.trim().length > 0,
    });
    const { data: oslc } = useQuery({
        queryKey: ["oslcConfig"],
        queryFn: () => unwrap(api.GET("/api/v1/oslc/config")),
    });
    const base = oslc?.resourceBase ?? "";
    const respond = (
        results: { "oslc:label": string; "rdf:resource": string }[],
    ) => {
        const message =
            "oslc-response:" + JSON.stringify({ "oslc:results": results });
        (window.opener ?? window.parent).postMessage(message, "*");
    };
    return (
        <div className="oslc-dialog">
            <h1>
                {domain === "cm"
                    ? t("Choose a change")
                    : t("Choose parts or documents")}
            </h1>
            <input
                type="search"
                autoFocus
                aria-label={t("Find by number or name")}
                placeholder={t("Find by number or name")}
                value={text}
                onChange={(e) => setText(e.target.value)}
            />
            {isLoading && text && <Loading />}
            <ul className="oslc-hits">
                {hits.map((o) => (
                    <li key={o.id}>
                        <button
                            onClick={() =>
                                respond([
                                    {
                                        "oslc:label": `${o.number} ${o.name}`,
                                        "rdf:resource": base + o.id,
                                    },
                                ])
                            }
                        >
                            <TypeIcon typeId={o.typeId} />{" "}
                            <strong>{o.number}</strong> {o.name}{" "}
                            <span className="version">{o.latest.version}</span>
                            <StateBadge state={o.latest.state} />
                        </button>
                    </li>
                ))}
            </ul>
            <div className="oslc-actions">
                <button className="btn" onClick={() => respond([])}>
                    {t("Cancel")}
                </button>
            </div>
        </div>
    );
}

/** OSLC small preview: what another tool shows when hovering a link to an object. */
export function OslcPreviewPage() {
    const { id } = useParams();
    const { data } = useQuery({
        queryKey: ["object", id],
        queryFn: () =>
            unwrap(
                api.GET("/api/v1/objects/{id}", {
                    params: { path: { id: id! } },
                }),
            ),
        enabled: !!id,
    });
    if (!data) return <Loading />;
    const description = data.latest.attributes?.description;
    return (
        <div className="oslc-preview">
            <div className="oslc-preview-head">
                <TypeIcon typeId={data.typeId} /> <strong>{data.number}</strong>{" "}
                <span className="version">{data.latest.version}</span>
                <StateBadge state={data.latest.state} />
            </div>
            <div className="oslc-preview-name">{data.name}</div>
            {typeof description === "string" && (
                <p className="muted">{description}</p>
            )}
            <a href={`/objects/${data.id}`} target="_blank" rel="noreferrer">
                {t("Open in PLM")} <LinkIcon size={12} aria-hidden />
            </a>
        </div>
    );
}
