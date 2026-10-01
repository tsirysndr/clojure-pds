import { describe, expect, it } from "vitest";
import en from "./locales/en.json";
import fr from "./locales/fr.json";
import pt from "./locales/pt.json";
import es from "./locales/es.json";
import it_ from "./locales/it.json";
import { detectLanguage, errorMessage, setupI18n } from "./index";

function keys(value: Record<string, unknown>, prefix = ""): string[] {
  return Object.entries(value).flatMap(([key, entry]) =>
    typeof entry === "object" && entry !== null
      ? keys(entry as Record<string, unknown>, `${prefix}${key}.`)
      : [`${prefix}${key}`],
  );
}

describe("translations", () => {
  it("every language carries every key, so nothing falls back silently", () => {
    const base = keys(en).sort();
    for (const locale of [fr, pt, es, it_]) {
      expect(keys(locale).sort()).toEqual(base);
    }
  });

  it("detects a supported browser language and falls back to English", () => {
    expect(detectLanguage(null, ["fr-FR", "en-US"])).toBe("fr");
    expect(detectLanguage("pt", ["en-US"])).toBe("pt");
    expect(detectLanguage(null, ["de-DE"])).toBe("en");
  });

  it("falls back to a generic message for unknown error codes", () => {
    const i18n = setupI18n("en");
    expect(errorMessage("something_unmapped", i18n.t)).toMatch(/went wrong/i);
  });
});
