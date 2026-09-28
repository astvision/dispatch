import { theme as antdTheme } from "antd";
import { describe, expect, it } from "vitest";
import { BOARD } from "./board";
import { DARK, LIGHT } from "./mini/world";
import { appTheme } from "./theme";

describe("the theme", () => {
  it("is the desktop's board outside Telegram, dark whatever the computer prefers", () => {
    const board = appTheme(false, false);

    expect(board.algorithm).toBe(antdTheme.darkAlgorithm);
    expect(board.token?.colorBgLayout).toBe(BOARD.ground);
    expect(board.token?.colorPrimary).toBe(BOARD.amber);
    expect(board.token?.colorTextLightSolid).toBe(BOARD.amberInk);
  });

  it("uses the world's palette in the scheme Telegram is in", () => {
    const dark = appTheme(true, true);
    const light = appTheme(true, false);

    expect(dark?.algorithm).toBe(antdTheme.darkAlgorithm);
    expect(dark?.token?.colorPrimary).toBe(DARK.button);
    expect(dark?.token?.colorTextBase).toBe(DARK.ink);
    expect(light?.algorithm).toBe(antdTheme.defaultAlgorithm);
    expect(light?.token?.colorBgContainer).toBe(LIGHT.slip);
  });

  /** antd writes colorTextLightSolid on its primary fill; white on amber would be unreadable. */
  it("labels the amber primary in the dark ink", () => {
    expect(appTheme(true, true)?.token?.colorTextLightSolid).toBe(DARK.buttonInk);
  });
});
