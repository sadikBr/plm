/**
 * Interface translations (roadmap 3.12), gettext style: the English text is the key.
 *
 *   t("Check out")                          → "Extraire" in French
 *   t("Shared with {count}", { count: 3 })  → placeholders in braces
 *   tn("{n} object", "{n} objects", n)      → picks singular or plural, then translates
 *
 * Catalogs are `locales/<lang>/<area>.json` (one file per part of the UI, so they're edited
 * independently), merged at start. A missing entry shows the English text. Call `t` while
 * rendering, never at module load: the language can change while the app runs (the whole tree
 * re-renders then).
 */

export const LANGUAGES = [
  { code: "en", name: "English" },
  { code: "fr", name: "Français" },
  { code: "de", name: "Deutsch" },
] as const;
export type Language = (typeof LANGUAGES)[number]["code"];

type Catalog = Record<string, string>;

const files = import.meta.glob<Catalog>("./locales/*/*.json", { eager: true, import: "default" });
const catalogs: Partial<Record<Language, Catalog>> = {};
for (const [path, entries] of Object.entries(files)) {
  const lang = path.split("/")[2] as Language;
  catalogs[lang] = { ...catalogs[lang], ...entries };
}

let current: Language = "en";

export function isLanguage(code: unknown): code is Language {
  return LANGUAGES.some((l) => l.code === code);
}

/** The browser's language if we have it, else English. */
export function browserLanguage(): Language {
  const code = (typeof navigator !== "undefined" ? navigator.language : "en").toLowerCase().split("-")[0];
  return isLanguage(code) ? code : "en";
}

export function setLanguage(lang: Language) {
  current = lang;
  if (typeof document !== "undefined") document.documentElement.lang = lang;
}

export function language(): Language {
  return current;
}

/** BCP 47 tag for Intl formatting. */
export function locale(): string {
  return current === "en" ? "en-US" : current === "fr" ? "fr-FR" : "de-DE";
}

function fill(text: string, vars?: Record<string, string | number>): string {
  if (!vars) return text;
  return text.replace(/\{(\w+)\}/g, (m, name: string) => (name in vars ? String(vars[name]) : m));
}

export function t(text: string, vars?: Record<string, string | number>): string {
  const translated = current === "en" ? text : (catalogs[current]?.[text] ?? text);
  return fill(translated, vars);
}

/** Singular or plural by [count] (exposed as `{n}`), then translated. */
export function tn(one: string, other: string, count: number, vars?: Record<string, string | number>): string {
  return t(count === 1 ? one : other, { n: count, ...vars });
}

/** For tests: the merged catalog of a language. */
export function catalog(lang: Language): Catalog {
  return catalogs[lang] ?? {};
}
