import { useQuery } from "@tanstack/react-query";
import { CircleAlert, CircleCheck, FlaskConical } from "lucide-react";
import { useEffect, useState } from "react";
import { api, unwrap } from "../api/client";
import { t } from "../i18n/i18n";
import { ErrorBanner } from "./common";

type Kind = "CONDITION" | "VALUE";

/** Waits [ms] after the last change, so checks don't run on every keystroke. */
function useDebounced<T>(value: T, ms = 300): T {
  const [v, setV] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setV(value), ms);
    return () => clearTimeout(timer);
  }, [value, ms]);
  return v;
}

/**
 * Edits a CEL expression: checked on the server as you type (with where the problem is), with a
 * reference of the variables and a "try it on an object" box that shows what it gives there.
 * Used for action visibility, validation rules, formulas and lifecycle gates.
 */
export function ExpressionField({
  id,
  label,
  value,
  onChange,
  kind = "CONDITION",
  placeholder,
  hint,
  rows = 2,
  tryable = true,
}: {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  kind?: Kind;
  placeholder?: string;
  hint?: string;
  rows?: number;
  /** Offer "try on an object" (not for expressions about events, which have no object). */
  tryable?: boolean;
}) {
  const text = useDebounced(value.trim());
  const { data: check } = useQuery({
    queryKey: ["expressionCheck", kind, text],
    queryFn: () => unwrap(api.POST("/api/v1/expressions/check", { body: { expression: text, kind } })),
    enabled: text.length > 0,
    staleTime: Infinity,
  });
  const problem = text && check && !check.valid ? check.problems[0] : undefined;

  return (
    <div className="field wide expression-field">
      <label htmlFor={id}>{label}</label>
      <textarea
        id={id}
        className="code"
        rows={rows}
        spellCheck={false}
        value={value}
        placeholder={placeholder}
        aria-invalid={!!problem}
        aria-describedby={`${id}-status`}
        onChange={(e) => onChange(e.target.value)}
      />
      <span id={`${id}-status`} className={problem ? "expr-status bad" : "expr-status"}>
        {problem ? (
          <>
            <CircleAlert size={12} aria-hidden />
            {problem.line ? t("Line {line}, column {column}: ", { line: problem.line, column: problem.column ?? "" }) : ""}
            {problem.message}
          </>
        ) : text && check?.valid ? (
          <>
            <CircleCheck size={12} aria-hidden /> {t("Valid")}
          </>
        ) : (
          (hint ?? (kind === "CONDITION" ? t("A condition: true or false.") : t("A formula: its result is the value.")))
        )}
      </span>
      <details className="expr-help">
        <summary>{t("Variables and examples")}</summary>
        <ul>
          <li>
            <code>object.number</code>, <code>object.name</code>, <code>object.type</code>, <code>object.state</code>,{" "}
            <code>object.revision</code>, <code>object.version</code>, <code>object.checkedOut</code>, <code>object.context.name</code>,{" "}
            <code>object.context.kind</code>
          </li>
          <li>
            <code>object.attributes.&lt;name&gt;</code>
            {t(": every attribute of the type,")} <code>null</code> {t("when empty. Quantities are")} <code>{"{value, unit}"}</code>:{" "}
            <code>object.attributes.mass.value</code>
          </li>
          <li>
            <code>object.types</code>
            {t(": the type and its parents, e.g.")} <code>'Part' in object.types</code>
          </li>
          <li>
            <code>user.id</code>, <code>user.siteAdmin</code>, <code>user.permissions</code>
            {t(", e.g.")} <code>'MANAGE' in user.permissions</code>
          </li>
          <li>
            {t("In operation rules,")} <code>event.operation</code> ({t("e.g.")} <code>'object.check-in'</code>){t(", and")}{" "}
            <code>event.from</code>, <code>event.to</code>, <code>event.comment</code> {t("for set state,")} <code>event.comment</code>{" "}
            {t("for check-in,")} <code>event.number</code>, <code>event.name</code> {t("for rename")}
          </li>
          <li>
            {t("Examples:")} <code>object.state == 'RELEASED' &amp;&amp; object.attributes.source == 'make'</code> ·{" "}
            <code>object.attributes.description != null &amp;&amp; size(object.attributes.description) &gt; 10</code> ·{" "}
            <code>double(object.attributes.quantity) * object.attributes.unitCost</code>
          </li>
        </ul>
      </details>
      {tryable && <TryOnObject expression={text} kind={kind} disabled={!text || !!problem} />}
    </div>
  );
}

/** Evaluates the expression on an object found by number or name. */
function TryOnObject({ expression, kind, disabled }: { expression: string; kind: Kind; disabled: boolean }) {
  const [query, setQuery] = useState("");
  const [result, setResult] = useState<{ number: string; value?: unknown; error?: string | null } | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  const run = async () => {
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      const found = await unwrap(api.GET("/api/v1/objects", { params: { query: { q: query.trim(), limit: 1 } } }));
      const obj = found[0];
      if (!obj) {
        setResult({ number: query.trim(), error: t("No object you can read matches that.") });
        return;
      }
      const r = await unwrap(
        api.POST("/api/v1/objects/{id}/expression-model/evaluate", { params: { path: { id: obj.id } }, body: { expression, kind } }),
      );
      setResult({ number: obj.number, value: r.value, error: r.error ?? r.problems[0]?.message });
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="expr-try">
      <FlaskConical size={13} className="icon-muted" aria-hidden />
      <input
        aria-label={t("Try on object")}
        placeholder={t("Try on an object: number or name")}
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter") {
            e.preventDefault();
            if (!disabled && query.trim()) void run();
          }
        }}
      />
      <button type="button" className="btn small" disabled={disabled || !query.trim() || busy} onClick={() => void run()}>
        {t("Try")}
      </button>
      {result && (
        <span className={result.error ? "expr-result bad" : "expr-result"} role="status">
          {result.number}: {result.error ?? JSON.stringify(result.value)}
        </span>
      )}
      <ErrorBanner error={error} />
    </div>
  );
}
