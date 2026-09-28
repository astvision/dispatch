import type { ThemeConfig } from "antd";
import { boardTheme } from "./board";
import { worldTheme } from "./mini/world";
import { inTelegram, prefersDark } from "./telegram";

/** Inside Telegram, the Mini App's own palette (mini/world.ts) in the scheme Telegram is in; outside, the desktop's board. */
export function appTheme(inside: boolean = inTelegram, dark: boolean = prefersDark): ThemeConfig {
  return inside ? worldTheme(dark) : boardTheme();
}
