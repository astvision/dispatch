import { expect, test } from "@playwright/test";

// Setup needs a bot token that Telegram accepts, so its Telegram-facing calls are answered here; the page is the real
// bundled one. SetupApiTest proves the server writes what this page sends.
const state = {
  configExists: false, configFile: "/home/bold/.config/dispatch/dispatch.yaml", team: false,
  bot: { username: "e2e_bot", topicsEnabled: true }, members: [{ id: 100, name: "Bold" }], candidate: null, group: null,
  claudeFound: "/usr/local/bin/claude", authorEmail: "bold@example.com", hints: [], hintAfterSeconds: 20,
};

test("the setup's Advanced section writes a per-phase model", async ({ page }) => {
  let written: { projects: { plan?: unknown }[] } | null = null;
  await page.route("**/api/setup/state", (route) => route.fulfill({ json: state }));
  await page.route("**/api/setup/claude", (route) => route.fulfill({ json: { command: "/usr/local/bin/claude", version: "2.1.0 (Claude Code)" } }));
  await page.route("**/api/setup/folders", (route) => route.fulfill({
    json: { path: "/home/bold", parent: "/home", truncated: false, folders: [{ name: "alm", path: "/home/bold/alm", gitClone: true }] },
  }));
  await page.route("**/api/setup/project", (route) => route.fulfill({
    json: { folder: "/home/bold/alm", name: "alm", originUrl: null, originHadCredentials: false, baseBranch: "main" },
  }));
  await page.route("**/api/setup/write", async (route) => {
    written = route.request().postDataJSON();
    await route.fulfill({ json: { configFile: state.configFile, secretsFile: "/home/bold/.config/dispatch/dispatch.env" } });
  });

  await page.goto("/");
  await page.getByRole("button", { name: "Check", exact: true }).click();
  await page.getByRole("button", { name: "Next", exact: true }).click();
  await page.getByRole("button", { name: "Use alm", exact: true }).click();
  await page.getByText("Advanced").click();
  await page.getByRole("combobox", { name: "Planning model" }).click();
  await page.locator(".ant-select-dropdown:visible").getByTitle("Opus", { exact: true }).click();
  await page.getByRole("button", { name: "Add project", exact: true }).click();
  await page.getByRole("button", { name: "Next", exact: true }).click();
  await page.getByRole("button", { name: "Next", exact: true }).click();
  await page.getByRole("button", { name: "Write this setup", exact: true }).click();

  await expect(page.getByText("Dispatch is set up")).toBeVisible();
  expect(written!.projects[0].plan).toEqual({ model: "opus", effort: null });
});
