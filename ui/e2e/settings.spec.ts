import { expect, test } from "@playwright/test";
import { appendFileSync, existsSync } from "node:fs";
import { config, paths } from "./dispatch";

test("a setting is saved into the config, and the previous config is kept as .bak", async ({ page }) => {
  await page.goto("/settings");
  await page.getByLabel("Maximum concurrent runs").fill("3");
  await page.getByRole("button", { name: "Save", exact: true }).click();

  await expect(page.getByText("Saved. Restart to apply")).toBeVisible();
  expect(config()).toContain("  maxConcurrentRuns: 3\n");
  expect(config()).toContain("# The e2e team's Dispatch\n");
  expect(existsSync(`${paths().configFile}.bak`)).toBe(true);
});

test("a save against a config changed on disk is refused until the page reloads it", async ({ page }) => {
  await page.goto("/settings");
  await expect(page.getByLabel("Commit author name")).toHaveValue("Dispatch (acme)");
  appendFileSync(paths().configFile, "# changed by hand\n");

  await page.getByLabel("Commit author name").fill("Dispatch (e2e)");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByText("the config changed on disk since this page loaded it; reload to see the change")).toBeVisible();
  expect(config()).not.toContain("Dispatch (e2e)");

  await page.getByRole("button", { name: "Reload", exact: true }).click();
  await expect(page.getByText(/changed on disk/)).toBeHidden();
  await page.getByLabel("Commit author name").fill("Dispatch (e2e)");
  await page.getByRole("button", { name: "Save", exact: true }).click();

  await expect(page.getByText("Saved. Restart to apply")).toBeVisible();
  expect(config()).toContain("  authorName: 'Dispatch (e2e)'\n");
  expect(config()).toContain("# changed by hand\n");
});
