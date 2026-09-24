import type { GlobalToken } from "antd";
import type { CSSProperties } from "react";
import type { ThemeParams } from "../telegram";

/**
 * The Mini App's colours as CSS variables, straight from Telegram's theme parameters; antd's derived tokens stand in for
 * any parameter a client leaves out. Light: the tickets are the bright section colour on the grey ground, as Telegram's
 * own settings are. Dark: the other way round, so a ticket still reads as raised.
 */
export function worldStyle(params: ThemeParams | null, dark: boolean, token: GlobalToken): CSSProperties {
  const bg = params?.bg_color ?? token.colorBgContainer;
  const secondary = params?.secondary_bg_color ?? token.colorBgLayout;
  const vars: Record<string, string> = {
    "--ground": dark ? bg : secondary,
    "--slip": params?.section_bg_color ?? (dark ? secondary : bg),
    "--ink": params?.text_color ?? token.colorText,
    "--hint": params?.hint_color ?? token.colorTextSecondary,
    "--link": params?.link_color ?? token.colorLink,
    "--button": params?.button_color ?? token.colorPrimary,
    "--button-ink": params?.button_text_color ?? "#ffffff",
    "--danger": params?.destructive_text_color ?? token.colorError,
    "--rule": params?.section_separator_color ?? token.colorSplit,
    // Telegram has no "done" colour; a green tuned for each scheme's ground, only ever beside the word it marks.
    "--done": dark ? "#5fbf6f" : "#2e9d47",
  };
  return vars as CSSProperties;
}
