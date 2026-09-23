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
      // Every field is optional on Telegram's side, so each one is dropped rather than defaulted to a wrong colour.
      ...(params.button_color ? { colorPrimary: params.button_color } : {}),
      ...(params.bg_color ? { colorBgBase: params.bg_color } : {}),
      ...(params.text_color ? { colorTextBase: params.text_color } : {}),
      ...(params.link_color ? { colorLink: params.link_color } : {}),
    },
  };
}
