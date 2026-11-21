import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, Minus } from "lucide-react";
import { useState } from "react";
import { Link } from "react-router";
import { api, unwrap, type Schemas } from "../api/client";
import { useMe, type VersionedObject } from "../api/queries";
import { ErrorBanner, Loading, Panel } from "../components/common";
import { t } from "../i18n/i18n";
import { SelectionChecker } from "./OptionRules";

type Line = Schemas["ConfiguredLine"];

/** A line's condition, editable on the working copy: saved when the field is left or on Enter. */
function ConditionField({ obj, line, value }: { obj: VersionedObject; line: Line; value: string }) {
  const qc = useQueryClient();
  const [text, setText] = useState(value);
  const save = useMutation({
    mutationFn: () =>
      unwrap(
        api.PUT("/api/v1/objects/{id}/usages/{lineId}/condition", {
          params: { path: { id: obj.id, lineId: line.node.line.id } },
          body: { expression: text.trim() || null },
        }),
      ),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["configured", obj.id] });
      void qc.invalidateQueries({ queryKey: ["lineConditions"] });
    },
  });
  return (
    <span className="condition-field">
      <input
        aria-label={t("Condition of line {n}", { n: line.node.line.lineNumber })}
        className={`condition-input ${save.isError ? "invalid" : ""}`}
        placeholder={t("always")}
        value={text}
        onChange={(e) => setText(e.target.value)}
        onBlur={() => text !== value && save.mutate()}
        onKeyDown={(e) => e.key === "Enter" && save.mutate()}
      />
      {save.error && <ErrorBanner error={save.error} />}
    </span>
  );
}

/**
 * The 150% structure of a configurable part (roadmap 7.7): every line with its condition, marked
 * by whether it applies to the choices made. On your working copy, its own lines' conditions are
 * edited here; lower lines belong to their sub-assemblies.
 */
export function ConfiguredStructurePanel({ obj, set }: { obj: VersionedObject; set: Schemas["OptionSet"] }) {
  const { data: me } = useMe();
  const mine = !!me && obj.checkedOutBy === me.user.id;
  const [selection, setSelection] = useState<Record<string, string>>({});
  const [onlyApplying, setOnlyApplying] = useState(false);
  const { data, error } = useQuery({
    queryKey: ["configured", obj.id, selection],
    queryFn: () => unwrap(api.POST("/api/v1/objects/{id}/configured-structure", { params: { path: { id: obj.id } }, body: { selection } })),
  });
  // Your working copy's lines, for editing their conditions.
  const { data: wcLines } = useQuery({
    queryKey: ["usages", obj.id, true],
    queryFn: () => unwrap(api.GET("/api/v1/objects/{id}/usages", { params: { path: { id: obj.id }, query: { workingCopy: true } } })),
    enabled: mine,
  });
  const { data: wcConditions } = useQuery({
    queryKey: ["configured", obj.id, "wc-conditions"],
    queryFn: () =>
      unwrap(api.GET("/api/v1/objects/{id}/line-conditions", { params: { path: { id: obj.id }, query: { workingCopy: true } } })),
    enabled: mine,
  });
  const rows = (data?.lines ?? []).filter((l) => !onlyApplying || l.applies);
  return (
    <Panel
      title={t("150% structure")}
      note={
        data
          ? t("{applying} of {total} lines apply to these choices.", { applying: data.applying, total: data.total })
          : t("Every line, with the condition under which it's built.")
      }
      actions={
        <label className="check">
          <input type="checkbox" checked={onlyApplying} onChange={(e) => setOnlyApplying(e.target.checked)} /> {t("Only what applies")}
        </label>
      }
      flush
    >
      <div className="configured-choices">
        <SelectionChecker set={set} value={selection} onChange={setSelection} />
      </div>
      <ErrorBanner error={error} />
      {!data ? (
        <Loading />
      ) : (
        <table className="grid configured" aria-label={t("150% structure")}>
          <thead>
            <tr>
              <th className="applies-col" aria-label={t("Applies")} />
              <th>{t("Line")}</th>
              <th>{t("Part")}</th>
              <th>{t("Version")}</th>
              <th className="num">{t("Quantity")}</th>
              <th>{t("Condition")}</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((l) => (
              <tr key={l.node.path} className={l.applies ? "" : "excluded"}>
                <td className="applies-col">
                  {l.applies ? <Check size={14} aria-label={t("Applies")} /> : <Minus size={14} aria-label={t("Doesn't apply")} />}
                </td>
                <td className="nowrap muted" style={{ paddingLeft: 8 + (l.node.level - 1) * 16 }}>
                  {l.node.path}
                </td>
                <td>
                  <Link to={`/objects/${l.node.line.child.id}`}>{l.node.line.child.number}</Link> {l.node.line.child.name}
                </td>
                <td>{l.node.line.child.version}</td>
                <td className="num">{l.node.line.quantity}</td>
                <td>
                  {mine && l.node.level === 1 && wcLines ? (
                    (() => {
                      const wc = wcLines.find((w) => w.lineNumber === l.node.line.lineNumber && w.child.id === l.node.line.child.id);
                      if (!wc) return <code>{l.condition}</code>;
                      const current = wcConditions?.find((c) => c.lineId === wc.id)?.expression ?? "";
                      return (
                        <ConditionField
                          key={`${wc.id}:${current}`}
                          obj={obj}
                          line={{ ...l, node: { ...l.node, line: { ...l.node.line, id: wc.id } } }}
                          value={current}
                        />
                      );
                    })()
                  ) : l.condition ? (
                    <code className="condition">{l.condition}</code>
                  ) : (
                    <span className="muted small">{t("always")}</span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {!mine && (
        <p className="muted small configured-hint">
          {t("Check it out to write its lines' conditions, e.g. MOTOR=HT and not COLOR in (RED, BLUE).")}
        </p>
      )}
    </Panel>
  );
}
