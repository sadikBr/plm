import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckCircle2, Lock, Plus, ShieldAlert, ShieldCheck, Trash2, X } from "lucide-react";
import { useState } from "react";
import { api, unwrap, type Schemas } from "../api/client";
import { findPrincipalByUsername, useContextsOfKind } from "../api/queries";
import { Dialog, EmptyState, ErrorBanner, Loading, Panel, PrincipalName, When } from "../components/common";
import { t } from "../i18n/i18n";

type Label = Schemas["SecurityLabel"];

const useLabels = () => useQuery({ queryKey: ["securityLabels"], queryFn: () => unwrap(api.GET("/api/v1/security-labels")) });

/** An object's security labels (roadmap 9.7); managers of its context set them. */
export function SecurityLabelsPanel({ objectId }: { objectId: string }) {
  const qc = useQueryClient();
  const { data: labels } = useLabels();
  const { data: mine } = useQuery({
    queryKey: ["objectLabels", objectId],
    queryFn: () => unwrap(api.GET("/api/v1/objects/{id}/security-labels", { params: { path: { id: objectId } } })),
  });
  const [editing, setEditing] = useState(false);
  const set = useMutation({
    mutationFn: (values: Record<string, string | null>) =>
      unwrap(api.PUT("/api/v1/objects/{id}/security-labels", { params: { path: { id: objectId } }, body: { values: values as Record<string, string> } })),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["objectLabels", objectId] }),
  });
  if (!labels?.length) return null;
  return (
    <Panel
      title={t("Security labels")}
      note={mine?.length ? t("Only people cleared for these, or covered by an agreement, see this object") : t("Not labelled")}
      actions={
        <button className="btn small" onClick={() => setEditing(!editing)}>
          {editing ? t("Done") : t("Change")}
        </button>
      }
    >
      <ErrorBanner error={set.error} />
      {!editing ? (
        mine?.length ? (
          <div className="sec-labels">
            {mine.map((l) => (
              <span key={l.labelId} className="sec-label">
                <Lock size={12} aria-hidden /> {l.labelName}: <strong>{l.valueLabel}</strong>
              </span>
            ))}
          </div>
        ) : (
          <p className="muted small">{t("Anyone the object's context lets in can see it.")}</p>
        )
      ) : (
        <div className="sec-edit">
          {labels.map((l) => (
            <label key={l.id} className="field">
              <span>{l.name}</span>
              <select
                value={mine?.find((m) => m.labelId === l.id)?.valueKey ?? ""}
                onChange={(e) => set.mutate({ [l.id]: e.target.value || null })}
              >
                <option value="">{t("None")}</option>
                {l.values.map((v) => (
                  <option key={v.key} value={v.key}>
                    {v.label}
                  </option>
                ))}
              </select>
            </label>
          ))}
        </div>
      )}
    </Panel>
  );
}

/** Shown instead of an object a label keeps the person from: which labels, and what would let them in. */
export function LabelBlocked({ objectId }: { objectId: string }) {
  const { data, isLoading } = useQuery({
    queryKey: ["accessExplanation", objectId],
    queryFn: () => unwrap(api.GET("/api/v1/objects/{id}/access-explanation", { params: { path: { id: objectId } } })),
  });
  if (isLoading) return <Loading />;
  return (
    <div className="content">
      <div className="panel sec-blocked">
        <EmptyState
          icon={<ShieldAlert size={22} aria-hidden />}
          title={t("This object is protected by a security label")}
          hint={t("You need a clearance for each label below, or an agreement that covers this object. Ask your security officer.")}
        />
        <table className="grid" aria-label={t("Security labels")}>
          <thead>
            <tr>
              <th>{t("Label")}</th>
              <th>{t("Marked")}</th>
              <th>{t("Your clearance")}</th>
              <th>{t("Result")}</th>
            </tr>
          </thead>
          <tbody>
            {data?.checks.map((c) => (
              <tr key={c.labelName}>
                <td>{c.labelName}</td>
                <td>
                  <strong>{c.value}</strong>
                </td>
                <td>{c.clearance ?? <span className="muted">{t("none")}</span>}</td>
                <td>
                  {c.passed ? (
                    <span className="tag allow">
                      <CheckCircle2 size={12} aria-hidden /> {c.through}
                    </span>
                  ) : (
                    <span className="tag deny">{t("not cleared")}</span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

/** Administration → Security labels: the labels, who is cleared for what, and agreements. */
export function SecurityAdmin() {
  const { data: labels, isLoading } = useLabels();
  const [creating, setCreating] = useState(false);
  if (isLoading) return <Loading />;
  return (
    <div className="stack">
      <Panel
        title={t("Labels")}
        note={t("Values go from least to most restrictive; a clearance covers its value and those below it")}
        actions={
          <button className="btn small primary" onClick={() => setCreating(true)}>
            <Plus size={13} aria-hidden /> {t("New label")}
          </button>
        }
        flush
      >
        {!labels?.length ? (
          <p className="empty">{t("No security labels yet.")}</p>
        ) : (
          <table className="grid" aria-label={t("Labels")}>
            <tbody>
              {labels.map((l) => (
                <tr key={l.id}>
                  <td>
                    <strong>{l.name}</strong> <span className="muted small">{l.key}</span>
                    {l.description && <div className="muted small">{l.description}</div>}
                  </td>
                  <td>
                    <span className="sec-scale">
                      {l.values.map((v, i) => (
                        <span key={v.key} className="sec-step" style={{ opacity: 0.55 + (0.45 * (i + 1)) / l.values.length }}>
                          {v.label}
                        </span>
                      ))}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Panel>
      {labels && labels.length > 0 && <Clearances labels={labels} />}
      {labels && labels.length > 0 && <Agreements labels={labels} />}
      {creating && <LabelDialog onClose={() => setCreating(false)} />}
    </div>
  );
}

function valueLabel(labels: Label[], labelId: string, key: string) {
  const l = labels.find((x) => x.id === labelId);
  return `${l?.name ?? "?"}: ${l?.values.find((v) => v.key === key)?.label ?? key}`;
}

function Clearances({ labels }: { labels: Label[] }) {
  const qc = useQueryClient();
  const { data } = useQuery({ queryKey: ["clearances"], queryFn: () => unwrap(api.GET("/api/v1/clearances")) });
  const [username, setUsername] = useState("");
  const [labelId, setLabelId] = useState(labels[0]!.id);
  const [valueKey, setValueKey] = useState(labels[0]!.values.at(-1)!.key);
  const [expires, setExpires] = useState("");
  const [lookupError, setLookupError] = useState<unknown>();
  const refresh = () => void qc.invalidateQueries({ queryKey: ["clearances"] });
  const grant = useMutation({
    mutationFn: async () => {
      const p = await findPrincipalByUsername(username.trim());
      return unwrap(
        api.POST("/api/v1/clearances", {
          body: { principalId: p.id, labelId, valueKey, expiresAt: expires ? new Date(expires).toISOString() : null },
        }),
      );
    },
    onSuccess: () => {
      setUsername("");
      refresh();
    },
    onError: setLookupError,
  });
  const revoke = useMutation({
    mutationFn: ({ principalId, labelId }: { principalId: string; labelId: string }) =>
      unwrap(api.DELETE("/api/v1/clearances/{principalId}/{labelId}", { params: { path: { principalId, labelId } } })),
    onSuccess: refresh,
  });
  const label = labels.find((l) => l.id === labelId);
  return (
    <Panel title={t("Clearances")} note={t("Who may see objects marked up to which value")} flush>
      <ErrorBanner error={lookupError ?? revoke.error} />
      <table className="grid" aria-label={t("Clearances")}>
        <thead>
          <tr>
            <th>{t("Person")}</th>
            <th>{t("Cleared for")}</th>
            <th>{t("Until")}</th>
            <th>{t("Granted")}</th>
            <th aria-label={t("Actions")} />
          </tr>
        </thead>
        <tbody>
          {data?.map((c) => (
            <tr key={c.principalId + c.labelId}>
              <td>
                <PrincipalName id={c.principalId} />
              </td>
              <td>
                <ShieldCheck size={13} aria-hidden className="muted" /> {valueLabel(labels, c.labelId, c.valueKey)}
              </td>
              <td className="small">{c.expiresAt ? <When iso={c.expiresAt} /> : t("no end")}</td>
              <td className="small">
                <When iso={c.grantedAt} />
              </td>
              <td style={{ width: 40 }}>
                <button
                  className="icon-btn"
                  aria-label={t("Revoke")}
                  onClick={() => revoke.mutate({ principalId: c.principalId, labelId: c.labelId })}
                >
                  <X size={13} />
                </button>
              </td>
            </tr>
          ))}
          <tr>
            <td>
              <input
                aria-label={t("Username")}
                placeholder={t("Username")}
                value={username}
                onChange={(e) => setUsername(e.target.value)}
              />
            </td>
            <td className="sec-grant">
              <select
                aria-label={t("Label")}
                value={labelId}
                onChange={(e) => {
                  setLabelId(e.target.value);
                  setValueKey(labels.find((l) => l.id === e.target.value)!.values.at(-1)!.key);
                }}
              >
                {labels.map((l) => (
                  <option key={l.id} value={l.id}>
                    {l.name}
                  </option>
                ))}
              </select>
              <select aria-label={t("Up to")} value={valueKey} onChange={(e) => setValueKey(e.target.value)}>
                {label?.values.map((v) => (
                  <option key={v.key} value={v.key}>
                    {v.label}
                  </option>
                ))}
              </select>
            </td>
            <td>
              <input type="date" aria-label={t("Until")} value={expires} onChange={(e) => setExpires(e.target.value)} />
            </td>
            <td colSpan={2}>
              <button className="btn small primary" disabled={!username.trim() || grant.isPending} onClick={() => grant.mutate()}>
                {t("Grant")}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </Panel>
  );
}

function Agreements({ labels }: { labels: Label[] }) {
  const qc = useQueryClient();
  const { data } = useQuery({ queryKey: ["agreements"], queryFn: () => unwrap(api.GET("/api/v1/agreements")) });
  const { data: products } = useContextsOfKind("PRODUCT");
  const [creating, setCreating] = useState(false);
  const remove = useMutation({
    mutationFn: (id: string) => unwrap(api.DELETE("/api/v1/agreements/{id}", { params: { path: { id } } })),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["agreements"] }),
  });
  const [now] = useState(() => Date.now());
  return (
    <Panel
      title={t("Agreements")}
      note={t("Time-limited access for named people to named products or objects, e.g. a technical assistance agreement")}
      actions={
        <button className="btn small" onClick={() => setCreating(true)}>
          <Plus size={13} aria-hidden /> {t("New agreement")}
        </button>
      }
      flush
    >
      <ErrorBanner error={remove.error} />
      {!data?.length ? (
        <p className="empty">{t("No agreements yet.")}</p>
      ) : (
        <table className="grid" aria-label={t("Agreements")}>
          <thead>
            <tr>
              <th>{t("Agreement")}</th>
              <th>{t("Covers")}</th>
              <th>{t("People")}</th>
              <th>{t("Valid")}</th>
              <th aria-label={t("Actions")} />
            </tr>
          </thead>
          <tbody>
            {data.map((a) => {
              const current = a.active && new Date(a.validFrom).getTime() <= now && (!a.validTo || new Date(a.validTo).getTime() > now);
              return (
                <tr key={a.id}>
                  <td>
                    <strong>{a.name}</strong>
                    <div className="muted small">{valueLabel(labels, a.labelId, a.valueKey)}</div>
                  </td>
                  <td className="small">
                    {[
                      ...a.contextIds.map((c) => products?.find((p) => p.id === c)?.name ?? c),
                      ...a.objectIds.map(() => t("an object")),
                    ].join(", ")}
                  </td>
                  <td className="small">
                    {a.principals.map((p, i) => (
                      <span key={p}>
                        {i > 0 && ", "}
                        <PrincipalName id={p} />
                      </span>
                    ))}
                  </td>
                  <td className="small">
                    <span className={current ? "tag allow" : "tag"}>{current ? t("current") : t("not current")}</span>{" "}
                    {a.validTo ? (
                      <>
                        {t("until")} <When iso={a.validTo} />
                      </>
                    ) : (
                      t("no end")
                    )}
                  </td>
                  <td style={{ width: 40 }}>
                    <button className="icon-btn" aria-label={t("Delete")} onClick={() => remove.mutate(a.id)}>
                      <Trash2 size={13} />
                    </button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
      {creating && <AgreementDialog labels={labels} onClose={() => setCreating(false)} />}
    </Panel>
  );
}

function AgreementDialog({ labels, onClose }: { labels: Label[]; onClose: () => void }) {
  const qc = useQueryClient();
  const { data: products } = useContextsOfKind("PRODUCT");
  const [name, setName] = useState("");
  const [labelId, setLabelId] = useState(labels[0]!.id);
  const [valueKey, setValueKey] = useState(labels[0]!.values.at(-1)!.key);
  const [contextId, setContextId] = useState("");
  const [people, setPeople] = useState("");
  const [validTo, setValidTo] = useState("");
  const save = useMutation({
    mutationFn: async () => {
      const principals = await Promise.all(
        people
          .split(/[,\s]+/)
          .filter(Boolean)
          .map(async (u) => (await findPrincipalByUsername(u)).id),
      );
      return unwrap(
        api.POST("/api/v1/agreements", {
          body: {
            name,
            labelId,
            valueKey,
            principals,
            contextIds: [contextId || products![0]!.id],
            objectIds: [],
            active: true,
            validTo: validTo ? new Date(validTo).toISOString() : null,
          },
        }),
      );
    },
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["agreements"] });
      onClose();
    },
  });
  const label = labels.find((l) => l.id === labelId);
  return (
    <Dialog
      open
      title={t("New agreement")}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            {t("Cancel")}
          </button>
          <button className="btn primary" disabled={!name.trim() || !people.trim() || save.isPending} onClick={() => save.mutate()}>
            {t("Create")}
          </button>
        </>
      }
    >
      <ErrorBanner error={save.error} />
      <div className="field">
        <label htmlFor="ag-name">{t("Name")}</label>
        <input id="ag-name" value={name} onChange={(e) => setName(e.target.value)} placeholder={t("e.g. TAA-2026-14 with Acme Avionics")} />
      </div>
      <div className="pkg-form">
        <div className="field">
          <label htmlFor="ag-label">{t("Label")}</label>
          <select id="ag-label" value={labelId} onChange={(e) => setLabelId(e.target.value)}>
            {labels.map((l) => (
              <option key={l.id} value={l.id}>
                {l.name}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="ag-value">{t("Up to")}</label>
          <select id="ag-value" value={valueKey} onChange={(e) => setValueKey(e.target.value)}>
            {label?.values.map((v) => (
              <option key={v.key} value={v.key}>
                {v.label}
              </option>
            ))}
          </select>
        </div>
      </div>
      <div className="field">
        <label htmlFor="ag-context">{t("For the product")}</label>
        <select id="ag-context" value={contextId} onChange={(e) => setContextId(e.target.value)}>
          {products?.map((p) => (
            <option key={p.id} value={p.id}>
              {p.name}
            </option>
          ))}
        </select>
      </div>
      <div className="field">
        <label htmlFor="ag-people">{t("People (user names)")}</label>
        <input id="ag-people" value={people} onChange={(e) => setPeople(e.target.value)} placeholder="bob, carol" />
      </div>
      <div className="field">
        <label htmlFor="ag-to">{t("Ends")}</label>
        <input id="ag-to" type="date" value={validTo} onChange={(e) => setValidTo(e.target.value)} />
      </div>
    </Dialog>
  );
}

function LabelDialog({ onClose }: { onClose: () => void }) {
  const qc = useQueryClient();
  const [name, setName] = useState("");
  const [key, setKey] = useState("");
  const [values, setValues] = useState("");
  const parsed = values
    .split(",")
    .map((v) => v.trim())
    .filter(Boolean)
    .map((label) => ({
      label,
      key: label
        .toUpperCase()
        .replace(/[^A-Z0-9]+/g, "_")
        .replace(/^([0-9])/, "V$1"),
    }));
  const save = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/security-labels", { body: { key, name, values: parsed } })),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["securityLabels"] });
      onClose();
    },
  });
  return (
    <Dialog
      open
      title={t("New label")}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            {t("Cancel")}
          </button>
          <button
            className="btn primary"
            disabled={!name.trim() || !key || parsed.length === 0 || save.isPending}
            onClick={() => save.mutate()}
          >
            {t("Create")}
          </button>
        </>
      }
    >
      <ErrorBanner error={save.error} />
      <div className="field">
        <label htmlFor="lb-name">{t("Name")}</label>
        <input
          id="lb-name"
          value={name}
          onChange={(e) => {
            setName(e.target.value);
            setKey(
              e.target.value
                .toUpperCase()
                .replace(/[^A-Z0-9]+/g, "_")
                .replace(/^_+|_+$/g, ""),
            );
          }}
          placeholder={t("e.g. Export control")}
        />
      </div>
      <div className="field">
        <label htmlFor="lb-values">{t("Values, least restrictive first, separated by commas")}</label>
        <input id="lb-values" value={values} onChange={(e) => setValues(e.target.value)} placeholder="EAR99, ECCN 5A992, ITAR" />
      </div>
    </Dialog>
  );
}
