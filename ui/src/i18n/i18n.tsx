import { ConfigProvider } from "antd";
import enUS from "antd/locale/en_US";
import mnMN from "antd/locale/mn_MN";
import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { en } from "./en";
import { mn } from "./mn";

export type Language = "mn" | "en";
export type Key = keyof typeof en;
export type Translate = (key: Key, params?: Record<string, string | number>) => string;
type Remembered = Pick<Storage, "getItem" | "setItem">;

const STORED = "dispatch.language";
const DICTIONARIES: Record<Language, Record<Key, string>> = { en, mn };
let current: Language = "en";

/** The language the page's requests are sent in (api.ts): the provider keeps it. */
export const currentLanguage = () => current;

/** A remembered mn or en, else mn when the browser lists Mongolian, else English. Storage may throw: then the browser decides. */
export function detectLanguage(storage: Remembered | undefined, languages: readonly string[]): Language {
  try {
    const stored = storage?.getItem(STORED);
    if (stored === "mn" || stored === "en") return stored;
  } catch {
    // Blocked storage (a private window, cleared site data): the browser's languages still decide.
  }
  return languages.some((tag) => tag.toLowerCase().split("-")[0] === "mn") ? "mn" : "en";
}

function format(language: Language, key: Key, params?: Record<string, string | number>): string {
  const phrase = DICTIONARIES[language][key] ?? en[key];
  return params ? phrase.replace(/\{(\w+)\}/g, (whole, name: string) => (name in params ? String(params[name]) : whole)) : phrase;
}

interface Chosen {
  language: Language;
  choose: (language: Language) => void;
}

const LanguageContext = createContext<Chosen>({ language: "en", choose: () => {} });

function browserStorage(): Remembered | undefined {
  try {
    return window.localStorage;
  } catch {
    return undefined;
  }
}

/**
 * The page's language, with antd's own words (its locale) and <html lang> to match. The desktop detects and remembers it;
 * the Mini App passes {@code fixed}, since it speaks the bot's language.
 */
export function LanguageProvider({ fixed, storage = browserStorage(), languages = navigator.languages ?? [], children }: {
  fixed?: Language;
  storage?: Remembered;
  languages?: readonly string[];
  children: ReactNode;
}) {
  const [chosen, setChosen] = useState<Language>(() => fixed ?? detectLanguage(storage, languages));
  const language = fixed ?? chosen;
  current = language;
  useEffect(() => {
    document.documentElement.lang = language;
  }, [language]);
  const value = useMemo<Chosen>(() => ({
    language,
    choose: (next) => {
      if (fixed) return;
      try {
        storage?.setItem(STORED, next);
      } catch {
        // Remembered for this visit only.
      }
      setChosen(next);
    },
  }), [language, fixed, storage]);
  return (
    <LanguageContext.Provider value={value}>
      <ConfigProvider locale={language === "mn" ? mnMN : enUS}>{children}</ConfigProvider>
    </LanguageContext.Provider>
  );
}

export const useLanguage = () => useContext(LanguageContext);

/** t(key, params): the phrase in the page's language; English outside any provider, as the tests render. */
export function useT(): Translate {
  const { language } = useLanguage();
  return (key, params) => format(language, key, params);
}
