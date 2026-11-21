import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useNavigate } from "react-router";
import { api, unwrap } from "../api/client";
import { useHistory, type VersionedObject } from "../api/queries";
import { Dialog, ErrorBanner, stateLabel } from "../components/common";
import { t } from "../i18n/i18n";

/** Save As (roadmap 3.3): a copy with a new number, the latest version's values and files. */
export function SaveAsDialog({ obj, onClose }: { obj: VersionedObject; onClose: () => void }) {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [name, setName] = useState(() => t("Copy of {name}", { name: obj.name }));
  const [number, setNumber] = useState("");
  const [withFiles, setWithFiles] = useState(true);
  const save = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/api/v1/objects/{id}/save-as", {
          params: { path: { id: obj.id } },
          body: { name, number: number.trim() || null, withFiles },
        }),
      ),
    onSuccess: (copy) => {
      void qc.invalidateQueries({ queryKey: ["objects"] });
      onClose();
      navigate(`/objects/${copy.id}`);
    },
  });
  return (
    <Dialog
      open
      title={t("Save {number} as…", { number: obj.number })}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            {t("Cancel")}
          </button>
          <button className="btn primary" disabled={save.isPending || !name.trim()} onClick={() => save.mutate()}>
            {t("Save as")}
          </button>
        </>
      }
    >
      <div className="attr-form">
        <ErrorBanner error={save.error} />
        <p className="muted wide" style={{ margin: 0 }}>
          {t("A new object of the same type in the same place, starting at A.1 with the values of {number} {version}.", {
            number: obj.number,
            version: obj.version,
          })}
        </p>
        <div className="field wide">
          <label htmlFor="saveas-name">{t("Name")}</label>
          <input id="saveas-name" value={name} onChange={(e) => setName(e.target.value)} autoFocus />
        </div>
        <div className="field">
          <label htmlFor="saveas-number">{t("Number")}</label>
          <input id="saveas-number" value={number} onChange={(e) => setNumber(e.target.value)} placeholder={t("Assigned automatically")} />
        </div>
        <label className="check wide">
          <input type="checkbox" checked={withFiles} onChange={(e) => setWithFiles(e.target.checked)} />
          {t("Copy its files too")}
        </label>
      </div>
    </Dialog>
  );
}

/** Choose what to compare: two versions of this object, or its latest with another object's. */
export function CompareDialog({ obj, onClose }: { obj: VersionedObject; onClose: () => void }) {
  const navigate = useNavigate();
  const { data: history } = useHistory(obj.id);
  const [mode, setMode] = useState<"versions" | "object">("versions");
  const [left, setLeft] = useState("");
  const [right, setRight] = useState("");
  const [other, setOther] = useState("");
  const { data: matches } = useQuery({
    queryKey: ["compareFind", other],
    queryFn: () => unwrap(api.GET("/api/v1/objects", { params: { query: { q: other.trim(), limit: 8 } } })),
    enabled: mode === "object" && other.trim().length >= 2,
  });
  const [picked, setPicked] = useState<string>("");
  const versions = history ?? [];
  const l = left || versions[1]?.id || versions[0]?.id || "";
  const r = right || versions[0]?.id || "";

  const go = () => {
    const q =
      mode === "versions" ? `left=${obj.id}&leftIteration=${l}&right=${obj.id}&rightIteration=${r}` : `left=${obj.id}&right=${picked}`;
    onClose();
    navigate(`/compare?${q}`);
  };

  return (
    <Dialog
      open
      title={t("Compare {number}", { number: obj.number })}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            {t("Cancel")}
          </button>
          <button className="btn primary" disabled={mode === "versions" ? !l || !r || l === r : !picked} onClick={go}>
            {t("Compare")}
          </button>
        </>
      }
    >
      <div className="attr-form">
        <fieldset className="check-list wide" style={{ gridTemplateColumns: "1fr" }}>
          <legend>{t("Compare")}</legend>
          <label className="check">
            <input type="radio" name="cmp-mode" checked={mode === "versions"} onChange={() => setMode("versions")} />
            {t("Two versions of {number}", { number: obj.number })}
          </label>
          <label className="check">
            <input type="radio" name="cmp-mode" checked={mode === "object"} onChange={() => setMode("object")} />
            {t("{number} {version} with another object", { number: obj.number, version: obj.version })}
          </label>
        </fieldset>
        {mode === "versions" ? (
          <>
            <div className="field">
              <label htmlFor="cmp-left">{t("From")}</label>
              <select id="cmp-left" value={l} onChange={(e) => setLeft(e.target.value)}>
                {versions.map((v) => (
                  <option key={v.id} value={v.id}>
                    {v.version} · {stateLabel(v.state)}
                  </option>
                ))}
              </select>
            </div>
            <div className="field">
              <label htmlFor="cmp-right">{t("To")}</label>
              <select id="cmp-right" value={r} onChange={(e) => setRight(e.target.value)}>
                {versions.map((v) => (
                  <option key={v.id} value={v.id}>
                    {v.version} · {stateLabel(v.state)}
                  </option>
                ))}
              </select>
            </div>
            {versions.length < 2 && <p className="muted wide">{t("{number} has only one version so far.", { number: obj.number })}</p>}
          </>
        ) : (
          <div className="field wide">
            <label htmlFor="cmp-other">{t("Other object")}</label>
            <input id="cmp-other" value={other} onChange={(e) => setOther(e.target.value)} placeholder={t("Number or name")} autoFocus />
            <div className="pick-list" role="listbox" aria-label={t("Matching objects")}>
              {matches
                ?.filter((m) => m.id !== obj.id)
                .map((m) => (
                  <button
                    key={m.id}
                    role="option"
                    aria-selected={picked === m.id}
                    className={picked === m.id ? "selected" : undefined}
                    onClick={() => setPicked(m.id)}
                  >
                    <strong>{m.number}</strong> {m.name} <span className="muted">{m.version}</span>
                  </button>
                ))}
            </div>
          </div>
        )}
      </div>
    </Dialog>
  );
}
