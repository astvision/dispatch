/**
 * What Telegram tells the page when it opens it as a Mini App (spec: Security).
 *
 * The launch data arrives in the URL fragment, which browsers never send to a server, so the page reads it here and
 * puts it on every request itself. Nothing is loaded from telegram.org.
 */

/** The colours Telegram passes for the user's current theme; every field is optional and all are hex like "#1c1c1e". */
export interface ThemeParams {
  bg_color?: string;
  text_color?: string;
  hint_color?: string;
  link_color?: string;
  button_color?: string;
  button_text_color?: string;
  secondary_bg_color?: string;
  section_bg_color?: string;
  section_separator_color?: string;
  destructive_text_color?: string;
}

export interface Launch {
  /** The signed launch data, sent as `Authorization: tma <initData>`; null when the page was not opened by Telegram. */
  initData: string | null;
  theme: ThemeParams | null;
  /** Telegram's own idea of which theme is on, which the colours alone cannot always tell us. */
  dark: boolean;
}

/** Exported for the tests; the app uses the value read once at load. */
export function readLaunch(hash: string): Launch {
  // The fragment is a query string of its own: #tgWebAppData=...&tgWebAppThemeParams=...&tgWebAppColorScheme=dark
  const fragment = new URLSearchParams(hash.startsWith("#") ? hash.slice(1) : hash);
  const initData = fragment.get("tgWebAppData");
  const theme = parseTheme(fragment.get("tgWebAppThemeParams"));
  const scheme = fragment.get("tgWebAppColorScheme");
  return {
    initData: initData && initData.length > 0 ? initData : null,
    theme,
    // Some clients (Telegram Desktop) leave the scheme out; their background colour still says which one is on.
    dark: scheme === "dark" || scheme === "light" ? scheme === "dark" : isDarkColour(theme?.bg_color),
  };
}

/** Whether a "#rrggbb" colour is on the dark side; false for anything else, which keeps the light default. */
export function isDarkColour(hex: string | undefined): boolean {
  const match = /^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(hex ?? "");
  if (!match) return false;
  const [r, g, b] = match.slice(1).map((part) => parseInt(part, 16));
  return 0.299 * r + 0.587 * g + 0.114 * b < 128;
}

function parseTheme(raw: string | null): ThemeParams | null {
  if (!raw) return null;
  try {
    const parsed: unknown = JSON.parse(raw);
    // Telegram sends an object of colour names; anything else is not a theme we can use.
    return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? (parsed as ThemeParams) : null;
  } catch {
    return null;
  }
}

const launch = readLaunch(typeof window === "undefined" ? "" : window.location.hash);

/** Whether this page is running inside Telegram, which decides the shell, the menu and the theme. */
export const inTelegram = launch.initData !== null;

export const initData = launch.initData;
export const themeParams = launch.theme;
export const prefersDark = launch.dark;
