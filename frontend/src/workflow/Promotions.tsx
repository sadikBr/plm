import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowUpCircle, CircleStop } from "lucide-react";
import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { api, unwrap } from "../api/client";
import { useLifecycles, useMe, type VersionedObject } from "../api/queries";
import { pickLabel } from "../components/AttributeForm";
import { Dialog, ErrorBanner, Loading, Panel, PrincipalName, StateBadge, formatDate } from "../components/common";
import { PageHeader } from "../components/Layout";
import { t, tn } from "../i18n/i18n";
import { ProcessDetail } from "./Processes";

/** Promotion requests (roadmap 5.7): objects asked to move to a state, reviewed, then promoted. */

const STATUS_CLASS: Record<string, string> = {
  UNDER_REVIEW: "tag accent",
  APPROVED: "tag allow",
  REJECTED: "tag deny",
  CANCELLED: "tag warn",
};

export function promotionStatus(s: string): string {
  switch (s) {
    case "UNDER_REVIEW":
      return t("under review");
    case "APPROVED":
      return t("approved");
    case "REJECTED":
      return t("rejected");
    case "CANCELLED":
      return t("cancelled");
  }
  return s;
}

/** The mass operation: ask for the chosen objects to be promoted. */
export function RequestPromotionDialog({ objects, onClose }: { objects: VersionedObject[]; onClose: () => void }) {
  const navigate = useNavigate();
  const { data: lifecycles } = useLifecycles();
  const states = [
    ...new Map((lifecycles ?? []).flatMap((l) => l.states.map((s) => [s.key, pickLabel(s.labels, s.key)] as const))).entries(),
  ];
  const [name, setName] = useState(objects.length === 1 ? t("Release {number}", { number: objects[0]!.number }) : "");
  const [target, setTarget] = useState("RELEASED");
  const [comment, setComment] = useState("");
  const create = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/api/v1/promotion-requests", {
          body: { name: name.trim(), targetState: target, objectIds: objects.map((o) => o.id), comment: comment.trim() || null },
        }),
      ),
    onSuccess: (r) => navigate(`/promotions/${r.id}`),
  });
  const problems = (create.error as { problem?: { details?: { errors?: string[] } } } | null)?.problem?.details?.errors;
  return (
    <Dialog
      open
      title={tn("Request the promotion of {n} object", "Request the promotion of {n} objects", objects.length)}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            {t("Cancel")}
          </button>
          <button className="btn primary" disabled={!name.trim() || create.isPending} onClick={() => create.mutate()}>
            {t("Send for review")}
          </button>
        </>
      }
    >
      <div className="attr-form cols-2">
        <div className="field wide">
          <label htmlFor="promo-name">{t("Name")}</label>
          <input id="promo-name" value={name} onChange={(e) => setName(e.target.value)} autoFocus />
        </div>
        <div className="field">
          <label htmlFor="promo-target">{t("Promote to")}</label>
          <select id="promo-target" value={target} onChange={(e) => setTarget(e.target.value)}>
            {states.map(([k, l]) => (
              <option key={k} value={k}>
                {l}
              </option>
            ))}
          </select>
        </div>
        <div className="field wide">
          <label htmlFor="promo-comment">{t("Note for the reviewers")}</label>
          <textarea id="promo-comment" rows={2} value={comment} onChange={(e) => setComment(e.target.value)} />
        </div>
        <div className="banner info wide">
          {t(
            "The objects are locked while the request is reviewed: nobody can check them out, change their state, revise, move or delete them.",
          )}
        </div>
        {problems ? (
          <div className="banner error wide" role="alert">
            <ul style={{ margin: 0, paddingLeft: 16 }}>
              {problems.map((p) => (
                <li key={p}>{p}</li>
              ))}
            </ul>
          </div>
        ) : (
          <div className="wide">
            <ErrorBanner error={create.error} />
          </div>
        )}
      </div>
    </Dialog>
  );
}

export function PromotionsPage() {
  const { id } = useParams();
  const { data, isLoading, error } = useQuery({ queryKey: ["promotions"], queryFn: () => unwrap(api.GET("/api/v1/promotion-requests")) });
  return (
    <>
      <PageHeader
        icon={<ArrowUpCircle size={18} className="icon-muted" aria-hidden />}
        title={<span className="nm">{t("Promotion requests")}</span>}
        meta={<span className="sub">{t("Objects asked to move to a state: reviewed, then promoted together.")}</span>}
      />
      <div className="content reports-layout">
        <Panel title={t("Promotion requests")} flush>
          <ErrorBanner error={error} />
          {isLoading ? (
            <Loading />
          ) : !data?.length ? (
            <div className="empty">{t("No promotion request yet: select objects in a list and choose Request promotion.")}</div>
          ) : (
            <ul className="report-list" aria-label={t("Promotion requests")}>
              {data.map((r) => (
                <li key={r.id} className={r.id === id ? "on" : undefined}>
                  <Link to={`/promotions/${r.id}`}>
                    <span className="mono">{r.number}</span> {r.name}
                  </Link>
                  <span className={STATUS_CLASS[r.status] ?? "tag"}>{promotionStatus(r.status)}</span>
                </li>
              ))}
            </ul>
          )}
        </Panel>
        {id ? <PromotionDetail id={id} /> : data?.[0] && <PromotionDetail id={data[0].id} />}
      </div>
    </>
  );
}

function PromotionDetail({ id }: { id: string }) {
  const qc = useQueryClient();
  const { data: me } = useMe();
  const { data: r, error } = useQuery({
    queryKey: ["promotions", id],
    queryFn: () => unwrap(api.GET("/api/v1/promotion-requests/{id}", { params: { path: { id } } })),
    refetchInterval: (q) => (q.state.data?.status === "UNDER_REVIEW" ? 3000 : false),
  });
  const process = useQuery({
    queryKey: ["processes", r?.instanceId],
    queryFn: () => unwrap(api.GET("/api/v1/processes/{id}", { params: { path: { id: r!.instanceId! } } })),
    enabled: !!r?.instanceId,
    refetchInterval: r?.status === "UNDER_REVIEW" ? 3000 : false,
  });
  const cancel = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/promotion-requests/{id}/cancel", { params: { path: { id } } })),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["promotions"] }),
  });
  if (error) return <ErrorBanner error={error} />;
  if (!r) return <Loading />;
  return (
    <Panel
      title={`${r.number} · ${r.name}`}
      actions={
        <>
          <span className={STATUS_CLASS[r.status] ?? "tag"}>{promotionStatus(r.status)}</span>
          {r.status === "UNDER_REVIEW" && (r.createdBy === me?.user.id || me?.siteAdmin) && (
            <button className="btn small ghost danger" onClick={() => cancel.mutate()} disabled={cancel.isPending}>
              <CircleStop size={13} aria-hidden /> {t("Cancel the request")}
            </button>
          )}
        </>
      }
    >
      <div className="task-panel">
        <div className="muted">
          {t("To")} <StateBadge state={r.targetState} /> · {t("asked by")} <PrincipalName id={r.createdBy} /> · {formatDate(r.createdAt)}
          {r.decidedAt && <> · {t("decided {date}", { date: formatDate(r.decidedAt) })}</>}
        </div>
        {r.comment && <div className="instructions">{r.comment}</div>}
        <table className="grid" aria-label={t("Objects to promote")}>
          <thead>
            <tr>
              <th>{t("Number")}</th>
              <th>{t("Name")}</th>
              <th>{t("Version")}</th>
              <th>{t("State then")}</th>
              <th>{t("State now")}</th>
            </tr>
          </thead>
          <tbody>
            {r.items.map((i) => (
              <tr key={i.objectId}>
                <td className="mono">{i.number ? <Link to={`/objects/${i.objectId}`}>{i.number}</Link> : t("(no access)")}</td>
                <td>{i.name}</td>
                <td>{i.version}</td>
                <td>
                  <StateBadge state={i.stateBefore} />
                </td>
                <td>{i.stateNow && <StateBadge state={i.stateNow} />}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <ErrorBanner error={cancel.error} />
        {process.data && <ProcessDetail p={process.data} />}
      </div>
    </Panel>
  );
}
