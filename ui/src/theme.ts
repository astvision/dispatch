import { theme as antdTheme, type ThemeConfig } from "antd";
import { prefersDark, themeParams, type ThemeParams } from "./telegram";

/**
 * Telegram's colours for the user's current theme, mapped onto Ant Design's tokens, so the pages follow the app's
 * light and dark mode (spec: Pages, Inside Telegram). Outside Telegram this is undefined and antd's defaults stand,
 * which is what `dispatch ui` has always looked like.
 */
export function telegramTheme(params: ThemeParams | null = themeParams, dark: boolean = prefersDark): ThemeConfig | undefined {
  if (!params) return undefined;
  return {
    algorithm: dark ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
    token: {
      // Only the accent colours come from Telegram. The neutral scale - backgrounds, headings, secondary and
      // disabled text - is left to the algorithm, which derives it at contrasts that hold. Feeding it Telegram's
      // colorBgBase or colorTextBase collapsed that scale and made secondary text invisible on a dark theme.
      // Every field is optional on Telegram's side, so each one is dropped rather than defaulted to a wrong colour.
      ...(params.button_color ? { colorPrimary: params.button_color } : {}),
      ...(params.link_color ? { colorLink: params.link_color } : {}),
    },
  };
}
