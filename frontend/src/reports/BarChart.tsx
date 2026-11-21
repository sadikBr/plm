import { useState } from "react";
import { t } from "../i18n/i18n";

export type Bar = { key: string; label: string; count: number };

/**
 * Counts by category as horizontal bars (roadmap 3.8). One series, so one color and no legend; the
 * value sits at the end of each bar, and hovering shows the exact share. Long tails fold into
 * "Other" past [max] bars.
 */
export function BarChart({
  bars,
  max = 8,
  onSelect,
  wideLabels,
}: {
  bars: Bar[];
  max?: number;
  onSelect?: (key: string) => void;
  wideLabels?: boolean;
}) {
  const [hover, setHover] = useState<string | null>(null);
  const total = bars.reduce((s, b) => s + b.count, 0);
  const shown =
    bars.length > max
      ? [
          ...bars.slice(0, max - 1),
          {
            key: "__other",
            label: t("Other ({count})", { count: bars.length - max + 1 }),
            count: bars.slice(max - 1).reduce((s, b) => s + b.count, 0),
          },
        ]
      : bars;
  const top = Math.max(1, ...shown.map((b) => b.count));
  if (!shown.length) return <p className="empty">{t("Nothing to count.")}</p>;
  return (
    <div
      className={`viz-root bar-chart ${wideLabels ? "wide-labels" : ""}`}
      role="img"
      aria-label={shown.map((b) => `${b.label}: ${b.count}`).join(", ")}
    >
      {shown.map((b) => {
        const pct = (b.count / top) * 100;
        const clickable = onSelect && b.key !== "__other";
        return (
          <div
            key={b.key}
            className={`bar-row ${hover === b.key ? "hover" : ""} ${clickable ? "clickable" : ""}`}
            onMouseEnter={() => setHover(b.key)}
            onMouseLeave={() => setHover(null)}
            onClick={() => clickable && onSelect(b.key)}
          >
            <span className="bar-label" title={b.label}>
              {b.label}
            </span>
            <span className="bar-track">
              <span className="bar-fill" style={{ width: `max(${pct}%, 3px)` }} />
              <span className="bar-value">{b.count}</span>
              {hover === b.key && (
                <span className="viz-tooltip" role="tooltip">
                  <strong>{b.label}</strong>{" "}
                  {t("{count} of {total} ({percent}%)", {
                    count: b.count,
                    total,
                    percent: Math.round((b.count / Math.max(1, total)) * 100),
                  })}
                </span>
              )}
            </span>
          </div>
        );
      })}
    </div>
  );
}
