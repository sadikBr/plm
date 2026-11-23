import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckCircle2, Factory, Plus, RotateCw, Send, Trash2, TriangleAlert, X } from "lucide-react";
import { useState } from "react";
import { api, unwrap, type Schemas } from "../api/client";
import { useContextsOfKind, useTypes } from "../api/queries";
import { Dialog, EmptyState, ErrorBanner, Loading, Panel, When, typeLabel } from "../components/common";
import { t } from "../i18n/i18n";

type Status = Schemas["ObjectDistribution"];
type Target = Schemas["DistributionTarget"];
type PlantAttribute = Schemas["PlantAttribute"];

const STATUS_CLASS: Record<string, string> = {
    NOT_SENT: "tag",
    PENDING: "tag accent",
    SENT: "tag accent",
    ACKNOWLEDGED: "tag allow",
    ERROR: "tag deny",
};

function statusLabel(s: string) {
    return (
        {
            NOT_SENT: t("not sent"),
            PENDING: t("waiting to send"),
            SENT: t("sent, awaiting answer"),
            ACKNOWLEDGED: t("taken by the ERP"),
            ERROR: t("problem"),
        }[s] ?? s
    );
}

/** Where an object stands at each ERP plant (roadmap 9.5): plant values, status, history, resend. */
export function DistributionTab({ objectId }: { objectId: string }) {
    const qc = useQueryClient();
    const { data, isLoading, error } = useQuery({
        queryKey: ["distribution", objectId],
        queryFn: () => unwrap(api.GET("/api/v1/objects/{id}/distribution", { params: { path: { id: objectId } } })),
        refetchInterval: (q) => (q.state.data?.some((d) => d.status === "PENDING") ? 2000 : false),
    });
    const { data: messages } = useQuery({
        queryKey: ["distribution", objectId, "messages", data?.map((d) => d.status).join()],
        queryFn: () => unwrap(api.GET("/api/v1/distribution/messages", { params: { query: { objectId } } })),
    });
    const refresh = () => void qc.invalidateQueries({ queryKey: ["distribution", objectId] });
    const send = useMutation({
        mutationFn: (targetId: string) =>
            unwrap(api.POST("/api/v1/objects/{id}/distribution/{targetId}/send", { params: { path: { id: objectId, targetId } } })),
        onSuccess: refresh,
    });
    if (isLoading) return <Loading />;
    if (!data?.length)
        return (
            <div className="panel">
                <EmptyState
                    icon={<Factory size={20} aria-hidden />}
                    title={t("No ERP plant takes this object")}
                    hint={t("Site administrators set up ERP targets and the rules that send objects to them.")}
                />
            </div>
        );
    return (
        <div className="stack">
            <ErrorBanner error={error ?? send.error} />
            <div className="dist-grid">
                {data.map((d) => (
                    <TargetCard
                        key={d.targetId}
                        objectId={objectId}
                        d={d}
                        onSend={() => send.mutate(d.targetId)}
                        sending={send.isPending}
                    />
                ))}
            </div>
            {messages && messages.length > 0 && (
                <Panel title={t("Messages")} flush>
                    <table className="grid" aria-label={t("Messages")}>
                        <thead>
                            <tr>
                                <th>{t("Plant")}</th>
                                <th>{t("Version")}</th>
                                <th>{t("Status")}</th>
                                <th>{t("Answer")}</th>
                                <th>{t("Made")}</th>
                            </tr>
                        </thead>
                        <tbody>
                            {messages.map((m) => (
                                <tr key={m.id}>
                                    <td>{data.find((d) => d.targetId === m.targetId)?.targetName ?? "—"}</td>
                                    <td>{m.version}</td>
                                    <td>
                                        <span
                                            className={
                                                m.status === "ACKNOWLEDGED"
                                                    ? "tag allow"
                                                    : m.status === "REJECTED" || m.status === "FAILED"
                                                      ? "tag deny"
                                                      : "tag accent"
                                            }
                                        >
                                            {m.status.toLowerCase()}
                                        </span>
                                        {m.attempts > 1 && <span className="muted small"> ×{m.attempts}</span>}
                                    </td>
                                    <td className="small">{m.answer ?? m.lastError ?? ""}</td>
                                    <td className="small">
                                        <When iso={m.createdAt} />
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </Panel>
            )}
        </div>
    );
}

function TargetCard({ objectId, d, onSend, sending }: { objectId: string; d: Status; onSend: () => void; sending: boolean }) {
    const qc = useQueryClient();
    const [values, setValues] = useState<Record<string, string>>(d.plantValues);
    const save = useMutation({
        mutationFn: () =>
            unwrap(
                api.PUT("/api/v1/objects/{id}/distribution/{targetId}/plant-values", {
                    params: { path: { id: objectId, targetId: d.targetId } },
                    body: values,
                }),
            ),
        onSuccess: () => qc.invalidateQueries({ queryKey: ["distribution", objectId] }),
    });
    const changed = d.attributes.some((a) => (values[a.name] ?? "") !== (d.plantValues[a.name] ?? ""));
    return (
        <Panel
            title={d.targetName}
            note={`${d.system} · ${t("plant")} ${d.plant}`}
            actions={
                <button className="btn small" onClick={onSend} disabled={sending}>
                    {d.status === "NOT_SENT" ? <Send size={13} aria-hidden /> : <RotateCw size={13} aria-hidden />}{" "}
                    {d.status === "NOT_SENT" ? t("Send now") : t("Send again")}
                </button>
            }
        >
            <ErrorBanner error={save.error} />
            <div className="dist-status">
                <span className={STATUS_CLASS[d.status] ?? "tag"}>{statusLabel(d.status)}</span>
                {d.version && <span className="muted small">{d.version}</span>}
                {d.erpKey && (
                    <span className="small">
                        {t("ERP number")} <strong>{d.erpKey}</strong>
                    </span>
                )}
                {d.updatedAt && (
                    <span className="muted small">
                        <When iso={d.updatedAt} />
                    </span>
                )}
            </div>
            {d.message && (
                <div className={d.status === "ERROR" ? "banner error" : "banner info"}>
                    {d.status === "ERROR" ? <TriangleAlert size={14} aria-hidden /> : <CheckCircle2 size={14} aria-hidden />} {d.message}
                </div>
            )}
            <p className="muted small">
                {d.ruleState
                    ? t("Sent automatically when it becomes {state}.", { state: d.ruleState })
                    : t("Sent only when someone sends it.")}
            </p>
            {d.attributes.length > 0 && (
                <div className="dist-values">
                    {d.attributes.map((a) => (
                        <div className="field" key={a.name}>
                            <label htmlFor={`pv-${d.targetId}-${a.name}`}>
                                {a.label}
                                {a.required && " *"}
                            </label>
                            <input
                                id={`pv-${d.targetId}-${a.name}`}
                                value={values[a.name] ?? ""}
                                placeholder={a.defaultValue ?? ""}
                                onChange={(e) => setValues({ ...values, [a.name]: e.target.value })}
                            />
                        </div>
                    ))}
                    <button className="btn small primary" disabled={!changed || save.isPending} onClick={() => save.mutate()}>
                        {t("Save plant values")}
                    </button>
                </div>
            )}
        </Panel>
    );
}

/** Administration → ERP distribution: targets (system + plant), their attributes and rules. */
export function DistributionAdmin() {
    const qc = useQueryClient();
    const { data, isLoading, error } = useQuery({
        queryKey: ["distributionTargets"],
        queryFn: () => unwrap(api.GET("/api/v1/distribution/targets")),
    });
    const [creating, setCreating] = useState(false);
    const [secret, setSecret] = useState<string | null>(null);
    const remove = useMutation({
        mutationFn: (id: string) => unwrap(api.DELETE("/api/v1/distribution/targets/{id}", { params: { path: { id } } })),
        onSuccess: () => qc.invalidateQueries({ queryKey: ["distributionTargets"] }),
    });
    if (isLoading) return <Loading />;
    return (
        <div className="stack">
            <ErrorBanner error={error ?? remove.error} />
            {secret && (
                <div className="banner ok">
                    <CheckCircle2 size={15} aria-hidden />{" "}
                    {t(
                        "Copy the target's secret now, it is shown once: the ERP checks message signatures with it, and uses it to fetch and acknowledge.",
                    )}{" "}
                    <code>{secret}</code>
                    <button className="icon-btn" aria-label={t("Close")} onClick={() => setSecret(null)}>
                        <X size={13} />
                    </button>
                </div>
            )}
            <div className="row">
                <button className="btn primary" onClick={() => setCreating(true)}>
                    <Plus size={14} aria-hidden /> {t("New ERP target")}
                </button>
            </div>
            {!data?.length && (
                <div className="panel">
                    <EmptyState
                        icon={<Factory size={20} aria-hidden />}
                        title={t("No ERP targets yet")}
                        hint={t("A target is an ERP system and plant; rules say which objects go there when they are released.")}
                    />
                </div>
            )}
            {data?.map((target) => (
                <TargetPanel key={target.id} target={target} onDelete={() => remove.mutate(target.id)} />
            ))}
            {creating && <TargetDialog onClose={() => setCreating(false)} onCreated={setSecret} />}
        </div>
    );
}

function TargetPanel({ target, onDelete }: { target: Target; onDelete: () => void }) {
    const qc = useQueryClient();
    const { data: types } = useTypes();
    const { data: products } = useContextsOfKind("PRODUCT");
    const [typeId, setTypeId] = useState("00000000-0000-7000-8000-000000000102");
    const [contextId, setContextId] = useState("");
    const [state, setState] = useState("RELEASED");
    const refresh = () => void qc.invalidateQueries({ queryKey: ["distributionTargets"] });
    const add = useMutation({
        mutationFn: () =>
            unwrap(
                api.POST("/api/v1/distribution/targets/{id}/rules", {
                    params: { path: { id: target.id } },
                    body: { typeId, contextId: contextId || null, state },
                }),
            ),
        onSuccess: refresh,
    });
    const remove = useMutation({
        mutationFn: (id: string) => unwrap(api.DELETE("/api/v1/distribution/rules/{id}", { params: { path: { id } } })),
        onSuccess: refresh,
    });
    const typeName = (id: string) => {
        const ty = types?.find((x) => x.id === id);
        return ty ? typeLabel(ty) : id;
    };
    return (
        <Panel
            title={target.name}
            note={`${target.system} · ${t("plant")} ${target.plant} · ${target.endpoint ?? t("the ERP fetches its messages")}`}
            actions={
                <>
                    {target.pending > 0 && <span className="tag accent">{t("{n} on the way", { n: target.pending })}</span>}
                    {target.failed > 0 && <span className="tag deny">{t("{n} failed", { n: target.failed })}</span>}
                    <button className="icon-btn" aria-label={t("Delete the target")} onClick={onDelete}>
                        <Trash2 size={14} />
                    </button>
                </>
            }
            flush
        >
            <ErrorBanner error={add.error ?? remove.error} />
            {target.attributes.length > 0 && (
                <p className="dist-attrs small">
                    {t("Plant attributes")}:{" "}
                    {target.attributes.map((a) => (
                        <span key={a.name} className="tag">
                            {a.label}
                            {a.required && " *"}
                        </span>
                    ))}
                </p>
            )}
            <table className="grid" aria-label={t("Rules")}>
                <thead>
                    <tr>
                        <th>{t("Type")}</th>
                        <th>{t("In")}</th>
                        <th>{t("When it becomes")}</th>
                        <th aria-label={t("Actions")} />
                    </tr>
                </thead>
                <tbody>
                    {target.rules.map((r) => (
                        <tr key={r.id}>
                            <td>{typeName(r.typeId)}</td>
                            <td>{r.contextId ? (products?.find((p) => p.id === r.contextId)?.name ?? r.contextId) : t("Any product")}</td>
                            <td>{r.state}</td>
                            <td style={{ width: 40 }}>
                                <button className="icon-btn" aria-label={t("Remove")} onClick={() => remove.mutate(r.id)}>
                                    <X size={13} />
                                </button>
                            </td>
                        </tr>
                    ))}
                    <tr>
                        <td>
                            <select aria-label={t("Type")} value={typeId} onChange={(e) => setTypeId(e.target.value)}>
                                {types?.map((ty) => (
                                    <option key={ty.id} value={ty.id}>
                                        {typeLabel(ty)}
                                    </option>
                                ))}
                            </select>
                        </td>
                        <td>
                            <select aria-label={t("In")} value={contextId} onChange={(e) => setContextId(e.target.value)}>
                                <option value="">{t("Any product")}</option>
                                {products?.map((p) => (
                                    <option key={p.id} value={p.id}>
                                        {p.name}
                                    </option>
                                ))}
                            </select>
                        </td>
                        <td>
                            <input
                                aria-label={t("When it becomes")}
                                value={state}
                                onChange={(e) => setState(e.target.value.toUpperCase())}
                            />
                        </td>
                        <td>
                            <button className="btn small" onClick={() => add.mutate()} disabled={add.isPending}>
                                <Plus size={13} aria-hidden /> {t("Add")}
                            </button>
                        </td>
                    </tr>
                </tbody>
            </table>
        </Panel>
    );
}

function TargetDialog({ onClose, onCreated }: { onClose: () => void; onCreated: (secret: string) => void }) {
    const qc = useQueryClient();
    const [name, setName] = useState("");
    const [system, setSystem] = useState("SAP S/4HANA");
    const [plant, setPlant] = useState("");
    const [endpoint, setEndpoint] = useState("");
    const [attributes, setAttributes] = useState<PlantAttribute[]>([
        { name: "procurementType", label: "Procurement type", required: true },
    ]);
    const create = useMutation({
        mutationFn: () =>
            unwrap(
                api.POST("/api/v1/distribution/targets", {
                    body: { name, system, plant, endpoint: endpoint || null, attributes, active: true },
                }),
            ),
        onSuccess: (r) => {
            void qc.invalidateQueries({ queryKey: ["distributionTargets"] });
            onCreated(r.secret);
            onClose();
        },
    });
    const set = (i: number, patch: Partial<PlantAttribute>) => setAttributes(attributes.map((a, j) => (j === i ? { ...a, ...patch } : a)));
    return (
        <Dialog
            open
            title={t("New ERP target")}
            onClose={onClose}
            footer={
                <>
                    <button className="btn" onClick={onClose}>
                        {t("Cancel")}
                    </button>
                    <button
                        className="btn primary"
                        disabled={!name.trim() || !plant.trim() || create.isPending}
                        onClick={() => create.mutate()}
                    >
                        {t("Create")}
                    </button>
                </>
            }
        >
            <ErrorBanner error={create.error} />
            <div className="field">
                <label htmlFor="t-name">{t("Name")}</label>
                <input id="t-name" value={name} onChange={(e) => setName(e.target.value)} placeholder={t("e.g. SAP – Shenzhen plant")} />
            </div>
            <div className="pkg-form">
                <div className="field">
                    <label htmlFor="t-system">{t("ERP system")}</label>
                    <input id="t-system" value={system} onChange={(e) => setSystem(e.target.value)} />
                </div>
                <div className="field">
                    <label htmlFor="t-plant">{t("Plant")}</label>
                    <input id="t-plant" value={plant} onChange={(e) => setPlant(e.target.value)} />
                </div>
            </div>
            <div className="field">
                <label htmlFor="t-endpoint">{t("Endpoint (optional)")}</label>
                <input
                    id="t-endpoint"
                    value={endpoint}
                    onChange={(e) => setEndpoint(e.target.value)}
                    placeholder="https://erp.example.com/plm/materials"
                />
                <small className="muted">{t("Empty: the ERP fetches its messages instead.")}</small>
            </div>
            <h3 className="group-title">{t("Plant attributes")}</h3>
            {attributes.map((a, i) => (
                <div className="row dist-attr-row" key={i}>
                    <input aria-label={t("Name")} value={a.name} onChange={(e) => set(i, { name: e.target.value })} />
                    <input aria-label={t("Label")} value={a.label} onChange={(e) => set(i, { label: e.target.value })} />
                    <label className="check">
                        <input type="checkbox" checked={a.required} onChange={(e) => set(i, { required: e.target.checked })} />
                        {t("Required")}
                    </label>
                    <button
                        className="icon-btn"
                        aria-label={t("Remove")}
                        onClick={() => setAttributes(attributes.filter((_, j) => j !== i))}
                    >
                        <X size={13} />
                    </button>
                </div>
            ))}
            <button className="btn small" onClick={() => setAttributes([...attributes, { name: "", label: "", required: false }])}>
                <Plus size={13} aria-hidden /> {t("Add an attribute")}
            </button>
        </Dialog>
    );
}
