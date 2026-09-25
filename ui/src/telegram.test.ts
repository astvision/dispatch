import { describe, expect, it } from "vitest";
import { readLaunch } from "./telegram";

describe("reading what Telegram put in the fragment", () => {
  it("takes the launch data and the theme out of the fragment", () => {
    const launch = readLaunch(
      "#tgWebAppData=user%3D%257B%2522id%2522%253A100%257D%26hash%3Dabc" +
        "&tgWebAppThemeParams=%7B%22bg_color%22%3A%22%231c1c1e%22%2C%22button_color%22%3A%22%232ea6ff%22%7D" +
        "&tgWebAppColorScheme=dark",
    );

    expect(launch.initData).toBe("user=%7B%22id%22%3A100%7D&hash=abc");
    expect(launch.theme?.bg_color).toBe("#1c1c1e");
    expect(launch.theme?.button_color).toBe("#2ea6ff");
    expect(launch.dark).toBe(true);
  });

  it("says it is not in Telegram when there is no launch data", () => {
    expect(readLaunch("").initData).toBeNull();
    expect(readLaunch("#").initData).toBeNull();
    expect(readLaunch("#tgWebAppData=").initData).toBeNull();
    expect(readLaunch("#other=1").initData).toBeNull();
  });

  it("survives theme params that are not a theme", () => {
    expect(readLaunch("#tgWebAppData=x&tgWebAppThemeParams=not-json").theme).toBeNull();
    expect(readLaunch("#tgWebAppData=x&tgWebAppThemeParams=%5B1%2C2%5D").theme).toBeNull();
    expect(readLaunch("#tgWebAppData=x").theme).toBeNull();
  });

  it("reads a light scheme as light", () => {
    expect(readLaunch("#tgWebAppData=x&tgWebAppColorScheme=light").dark).toBe(false);
    expect(readLaunch("#tgWebAppData=x").dark).toBe(false);
  });

  /** Telegram Desktop sent a dark theme without the scheme, and the page came up in light antd on a dark ground. */
  it("tells the scheme from the background when the client leaves the scheme out", () => {
    const theme = (bg: string) => `#tgWebAppData=x&tgWebAppThemeParams=${encodeURIComponent(JSON.stringify({ bg_color: bg }))}`;

    expect(readLaunch(theme("#17212b")).dark).toBe(true);
    expect(readLaunch(theme("#ffffff")).dark).toBe(false);
    expect(readLaunch(theme("#17212b") + "&tgWebAppColorScheme=light").dark).toBe(false);
    expect(readLaunch(theme("not-a-colour")).dark).toBe(false);
  });
});
