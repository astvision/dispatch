import { theme as antdTheme, type ThemeConfig } from "antd";

/** The desktop's board: dark slate, amber for what needs the owner, green for running, red for failed. Dark only. */
export const BOARD = {
  ground: "#10191b",
  panel: "#172427",
  strip: "#0c1416",
  ink: "#e4ece8",
  secondary: "#9fb4ae",
  hint: "#7d948f",
  rule: "#24363a",
  amber: "#f0b44c",
  amberInk: "#10191b",
  green: "#58c28d",
  red: "#ef6a5a",
} as const;

export const BOARD_FONT = `"Onest Variable", system-ui, sans-serif`;
export const BOARD_MONO = `"JetBrains Mono Variable", ui-monospace, monospace`;

export function boardTheme(): ThemeConfig {
  return {
    algorithm: antdTheme.darkAlgorithm,
    token: {
      colorBgBase: BOARD.ground,
      colorBgLayout: BOARD.ground,
      colorBgContainer: BOARD.panel,
      colorBgElevated: BOARD.panel,
      colorTextBase: BOARD.ink,
      colorTextSecondary: BOARD.secondary,
      colorTextTertiary: BOARD.hint,
      colorBorder: BOARD.rule,
      colorSplit: BOARD.rule,
      colorPrimary: BOARD.amber,
      // antd writes this on its primary fill: white on amber would be unreadable.
      colorTextLightSolid: BOARD.amberInk,
      colorLink: BOARD.amber,
      colorSuccess: BOARD.green,
      colorError: BOARD.red,
      colorWarning: BOARD.amber,
      fontFamily: BOARD_FONT,
      fontFamilyCode: BOARD_MONO,
      borderRadius: 5,
    },
    components: {
      // The rail: the current page amber with dark ink, the rest quiet, edge to edge.
      Menu: {
        itemBg: "transparent",
        itemColor: BOARD.secondary,
        itemHoverColor: BOARD.ink,
        itemSelectedBg: BOARD.amber,
        itemSelectedColor: BOARD.amberInk,
        itemBorderRadius: 0,
        itemMarginInline: 0,
        itemMarginBlock: 0,
        activeBarBorderWidth: 0,
      },
    },
  };
}
