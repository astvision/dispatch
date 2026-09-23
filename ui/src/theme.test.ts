import { theme as antdTheme } from "antd";
import { describe, expect, it } from "vitest";
import { telegramTheme } from "./theme";

describe("following Telegram's theme", () => {
  it("leaves antd's own look alone outside Telegram", () => {
    expect(telegramTheme(null, false)).toBeUndefined();
  });

  it("uses the dark algorithm when Telegram is dark", () => {
    const dark = telegramTheme({ bg_color: "#1c1c1e", button_color: "#2ea6ff" }, true);
    const light = telegramTheme({ bg_color: "#ffffff" }, false);

    expect(dark?.algorithm).toBe(antdTheme.darkAlgorithm);
    expect(dark?.token?.colorPrimary).toBe("#2ea6ff");
    expect(dark?.token?.colorBgBase).toBe("#1c1c1e");
    expect(light?.algorithm).toBe(antdTheme.defaultAlgorithm);
  });

  it("drops a colour Telegram did not send rather than inventing one", () => {
    const partial = telegramTheme({ bg_color: "#ffffff" }, false);

    expect(partial?.token).not.toHaveProperty("colorPrimary");
    expect(partial?.token).not.toHaveProperty("colorTextBase");
  });
});
