import { theme as antdTheme, type ThemeConfig } from "antd";
import type { CSSProperties } from "react";

/**
 * The Mini App's own palette, graphite and amber, one set per scheme. Telegram only decides which scheme is on
 * (telegram.ts); its colours are no longer used, because each client's theme gave the page a different and often
 * muddy look. Amber is the owner's colour, teal is navigation; both reach 4.5:1 as text on their own ground and slip.
 */
export interface Palette {
  ground: string;
  slip: string;
  ink: string;
  hint: string;
  link: string;
  button: string;
  buttonInk: string;
  danger: string;
  rule: string;
  done: string;
}

export const DARK: Palette = {
  ground: "#16181c",
  slip: "#22252b",
  ink: "#ecebe7",
  hint: "#8b8f98",
  link: "#5cc8c0",
  button: "#f2a93b",
  // Amber is light, so its label is dark: white on it would not reach 3:1.
  buttonInk: "#1a1408",
  danger: "#ef5b5b",
  rule: "#30343b",
  done: "#6cc57c",
};

export const LIGHT: Palette = {
  ground: "#f4f2ee",
  slip: "#ffffff",
  ink: "#1c1b19",
  hint: "#8a8780",
  link: "#0b7069",
  button: "#c77a0a",
  buttonInk: "#1a1408",
  danger: "#c83a3a",
  rule: "#e3dfd8",
  done: "#2e8b45",
};

export const paletteFor = (dark: boolean) => (dark ? DARK : LIGHT);

/** The palette as the CSS variables world.css reads. */
export function worldStyle(dark: boolean): CSSProperties {
  const p = paletteFor(dark);
  const vars: Record<string, string> = {
    "--ground": p.ground,
    "--slip": p.slip,
    "--ink": p.ink,
    "--hint": p.hint,
    "--link": p.link,
    "--button": p.button,
    "--button-ink": p.buttonInk,
    "--danger": p.danger,
    "--rule": p.rule,
    "--done": p.done,
  };
  return vars as CSSProperties;
}

/** The same palette for antd, so its inputs, lists and alerts inside the Mini App match the world around them. */
export function worldTheme(dark: boolean): ThemeConfig {
  const p = paletteFor(dark);
  return {
    algorithm: dark ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
    token: {
      colorBgBase: p.ground,
      colorTextBase: p.ink,
      colorBgLayout: p.ground,
      colorBgContainer: p.slip,
      colorBgElevated: p.slip,
      colorPrimary: p.button,
      // antd writes white on its primary fill; amber needs the dark label.
      colorTextLightSolid: p.buttonInk,
      colorLink: p.link,
      colorError: p.danger,
      colorSuccess: p.done,
      colorBorder: p.rule,
      colorSplit: p.rule,
    },
  };
}
