import { fireEvent, render, screen } from "@testing-library/react";
import { expect, test } from "vitest";
import { detectLanguage, LanguageProvider, useLanguage, useT } from "./i18n";
import { mn } from "./mn";

const blocked = {
  getItem: (): string | null => {
    throw new Error("blocked");
  },
  setItem: () => {
    throw new Error("blocked");
  },
};
const empty = { getItem: () => null, setItem: () => {} };

test("a remembered choice wins, then the browser's languages", () => {
  expect(detectLanguage({ getItem: () => "en", setItem: () => {} }, ["mn-MN"])).toBe("en");
  expect(detectLanguage(empty, ["en-US", "mn"])).toBe("mn");
  expect(detectLanguage(empty, ["MN-Cyrl-MN"])).toBe("mn");
  expect(detectLanguage(empty, ["en-US", "ru"])).toBe("en");
  expect(detectLanguage(empty, ["mnx"])).toBe("en");
  expect(detectLanguage({ getItem: () => "xx", setItem: () => {} }, ["mn"])).toBe("mn");
});

test("storage that throws still gives the browser's language", () => {
  expect(detectLanguage(blocked, ["mn"])).toBe("mn");
  expect(detectLanguage(undefined, ["en-GB"])).toBe("en");
});

function Probe() {
  const t = useT();
  const { choose } = useLanguage();
  return (
    <>
      <p>{t("nav.projects")}</p>
      <p>{t("strip.checksWarn.other", { count: 2 })}</p>
      <button onClick={() => choose("mn")}>mn</button>
    </>
  );
}

test("choosing Mongolian renders Mongolian at once and remembers it", () => {
  const saved: Record<string, string> = {};
  render(
    <LanguageProvider storage={{ getItem: (key) => saved[key] ?? null, setItem: (key, value) => { saved[key] = value; } }}
                      languages={["en-US"]}>
      <Probe />
    </LanguageProvider>,
  );
  expect(screen.getByText("Projects")).toBeInTheDocument();
  expect(screen.getByText("2 warnings")).toBeInTheDocument();

  fireEvent.click(screen.getByRole("button", { name: "mn" }));

  expect(screen.getByText("Төслүүд")).toBeInTheDocument();
  expect(screen.getByText("2 анхааруулга")).toBeInTheDocument();
  expect(saved["dispatch.language"]).toBe("mn");
  expect(document.documentElement.lang).toBe("mn");
});

test("the switch still works for the visit when storage refuses to remember", () => {
  render(<LanguageProvider storage={blocked} languages={["en-US"]}><Probe /></LanguageProvider>);

  fireEvent.click(screen.getByRole("button", { name: "mn" }));

  expect(screen.getByText("Төслүүд")).toBeInTheDocument();
});

test("the Mini App's fixed language ignores the browser and the switch", () => {
  render(<LanguageProvider fixed="mn" storage={blocked} languages={["en-US"]}><Probe /></LanguageProvider>);
  expect(screen.getByText("Төслүүд")).toBeInTheDocument();
});

test("a page outside any provider reads English, as the tests do", () => {
  render(<Probe />);
  expect(screen.getByText("Projects")).toBeInTheDocument();
});

// D-1's rule: no English word on a Mongolian page but code values, names and other programs' words. "Desktop" is what
// the code calls these pages, so it is the likeliest to slip in.
test("no Mongolian phrase calls the pages the desktop", () => {
  expect(Object.entries(mn).filter(([, phrase]) => /\bdesk(top)?\b(?!\.json)/i.test(phrase))).toEqual([]);
});
