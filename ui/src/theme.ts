import type { ThemeConfig } from "antd";
import { worldTheme } from "./mini/world";
import { inTelegram, prefersDark } from "./telegram";

/**
 * Inside Telegram, the Mini App's own palette (mini/world.ts) in the scheme Telegram is in. Outside Telegram this is
 * undefined and antd's defaults stand, which is what `dispatch ui` has always looked like.
 */
export function telegramTheme(inside: boolean = inTelegram, dark: boolean = prefersDark): ThemeConfig | undefined {
  return inside ? worldTheme(dark) : undefined;
}
