import { theme as antdTheme } from "antd";
import { describe, expect, it } from "vitest";
import { DARK, LIGHT } from "./mini/world";
import { telegramTheme } from "./theme";

describe("the Mini App's theme", () => {
  it("leaves antd's own look alone outside Telegram", () => {
    expect(telegramTheme(false, false)).toBeUndefined();
  });

  it("uses the world's palette in the scheme Telegram is in", () => {
    const dark = telegramTheme(true, true);
    const light = telegramTheme(true, false);

    expect(dark?.algorithm).toBe(antdTheme.darkAlgorithm);
    expect(dark?.token?.colorPrimary).toBe(DARK.button);
    expect(dark?.token?.colorTextBase).toBe(DARK.ink);
    expect(light?.algorithm).toBe(antdTheme.defaultAlgorithm);
    expect(light?.token?.colorBgContainer).toBe(LIGHT.slip);
  });

  /** antd writes colorTextLightSolid on its primary fill; white on amber would be unreadable. */
  it("labels the amber primary in the dark ink", () => {
    expect(telegramTheme(true, true)?.token?.colorTextLightSolid).toBe(DARK.buttonInk);
  });
});
