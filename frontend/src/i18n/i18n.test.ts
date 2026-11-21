import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { catalog, setLanguage, t, tn, type Language } from "./i18n";

const SRC = join(__dirname, "..");

function sources(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return name === "i18n" || name === "test" || name === "api" ? [] : sources(path);
    return /\.tsx?$/.test(name) && !/\.test\.tsx?$/.test(name) ? [path] : [];
  });
}

/** Every literal passed to t() or tn(), as written in the source. */
function keys(): Map<string, string> {
  const found = new Map<string, string>();
  const literal = String.raw`"((?:[^"\\]|\\.)*)"`;
  const patterns = [new RegExp(String.raw`\bt\(\s*` + literal, "g"), new RegExp(String.raw`\btn\(\s*` + literal + String.raw`\s*,\s*` + literal, "g")];
  for (const file of sources(SRC)) {
    const code = readFileSync(file, "utf8");
    for (const p of patterns) {
      for (const m of code.matchAll(p)) {
        for (const g of m.slice(1)) if (g !== undefined) found.set(JSON.parse(`"${g}"`) as string, file.slice(SRC.length + 1));
      }
    }
  }
  return found;
}

const placeholders = (s: string) => [...s.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort();

describe("translations (roadmap 3.12)", () => {
  afterEach(() => setLanguage("en"));

  it("fills placeholders and falls back to English", () => {
    setLanguage("fr");
    expect(t("Sign out")).toBe("Se déconnecter");
    expect(t("No translation for this {thing}", { thing: "x" })).toBe("No translation for this x");
    setLanguage("en");
    expect(tn("{n} object", "{n} objects", 1)).toBe("1 object");
    expect(tn("{n} object", "{n} objects", 3)).toBe("3 objects");
  });

  for (const lang of ["fr", "de"] as Language[]) {
    it(`has every interface text in ${lang}, with the same placeholders`, () => {
      const entries = catalog(lang);
      const missing: string[] = [];
      const wrong: string[] = [];
      for (const [key, file] of keys()) {
        const value = entries[key];
        if (value === undefined) missing.push(`${file}: ${key}`);
        else if (placeholders(value).join() !== placeholders(key).join()) wrong.push(`${key} → ${value}`);
      }
      expect(missing).toEqual([]);
      expect(wrong).toEqual([]);
    });
  }
});
