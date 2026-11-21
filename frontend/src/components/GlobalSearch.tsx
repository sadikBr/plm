import { Search } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router";
import { useObjectSearch } from "../api/queries";
import { t } from "../i18n/i18n";
import { StateBadge, TypeIcon } from "./common";

const SUGGESTIONS = 8;

/** Debounced value: follows [value] once it has been stable for [ms]. */
function useDebounced<T>(value: T, ms: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), ms);
    return () => clearTimeout(timer);
  }, [value, ms]);
  return debounced;
}

/**
 * Top-bar search: type a number or name, pick a suggestion with the arrow keys, Enter opens it
 * (or the full results page). Press "/" anywhere to focus.
 */
export function GlobalSearch() {
  const navigate = useNavigate();
  const input = useRef<HTMLInputElement>(null);
  const [text, setText] = useState("");
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const query = useDebounced(text.trim(), 200);
  const { data, isFetching } = useObjectSearch(query, SUGGESTIONS);
  const results = query ? (data?.pages[0] ?? []) : [];

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement;
      const typing = ["INPUT", "TEXTAREA", "SELECT"].includes(target.tagName) || target.isContentEditable;
      if (e.key === "/" && !typing) {
        e.preventDefault();
        input.current?.focus();
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, []);

  const go = (path: string) => {
    setOpen(false);
    setText("");
    input.current?.blur();
    navigate(path);
  };

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "ArrowDown") {
      e.preventDefault();
      setActive((i) => Math.min(i + 1, results.length));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setActive((i) => Math.max(i - 1, 0));
    } else if (e.key === "Enter" && text.trim()) {
      e.preventDefault();
      const hit = results[active];
      go(hit && active < results.length ? `/objects/${hit.id}` : `/search?q=${encodeURIComponent(text.trim())}`);
    } else if (e.key === "Escape") {
      setOpen(false);
      input.current?.blur();
    }
  };

  return (
    <div className="search">
      <Search size={14} className="search-icon" aria-hidden />
      <input
        ref={input}
        type="search"
        role="combobox"
        aria-label={t("Search")}
        aria-expanded={open && !!query}
        aria-controls="search-results"
        placeholder={t("Search parts and documents by number or name")}
        value={text}
        onChange={(e) => {
          setText(e.target.value);
          setActive(0);
          setOpen(true);
        }}
        onFocus={() => setOpen(true)}
        onBlur={() => setTimeout(() => setOpen(false), 150)}
        onKeyDown={onKeyDown}
      />
      {!text && <kbd aria-hidden>/</kbd>}
      {open && query && (
        <div className="search-results" id="search-results" role="listbox" aria-label={t("Search results")}>
          {results.length === 0 && <div className="search-footer">{isFetching ? t("Searching…") : t("No matches.")}</div>}
          {results.map((o, i) => (
            <div
              key={o.id}
              className="search-result"
              role="option"
              aria-selected={i === active}
              onMouseEnter={() => setActive(i)}
              onMouseDown={(e) => {
                e.preventDefault();
                go(`/objects/${o.id}`);
              }}
            >
              <TypeIcon typeId={o.typeId} />
              <span className="num">{o.number}</span>
              <span className="name">{o.name}</span>
              <span className="version">{o.version}</span>
              <StateBadge state={o.latest.state} />
            </div>
          ))}
          {results.length > 0 && (
            <div
              className="search-result search-footer"
              role="option"
              aria-selected={active === results.length}
              onMouseDown={(e) => {
                e.preventDefault();
                go(`/search?q=${encodeURIComponent(text.trim())}`);
              }}
            >
              {t("See all results for “{text}”", { text: text.trim() })}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
