import { expect, test, type Page } from "@playwright/test";
import path from "node:path";
import { config, paths } from "./dispatch";

async function choose(page: Page, select: string, option: string) {
  await page.getByRole("combobox", { name: select }).click();
  await page.locator(".ant-select-dropdown:visible").getByTitle(option, { exact: true }).click();
}

test("a project is added from the folder browser, edited and removed", async ({ page }) => {
  const clone = paths().cloneToAdd;
  await page.goto("/projects");

  await page.getByRole("button", { name: "Add a project", exact: true }).click();
  await page.getByRole("textbox", { name: "Folder" }).fill(path.dirname(clone));
  await page.getByRole("button", { name: "Go", exact: true }).click();
  await page.getByRole("button", { name: "Use life", exact: true }).click();
  await choose(page, "Planning model", "Opus");
  await page.getByRole("button", { name: "Add project", exact: true }).click();
  await expect(page.getByRole("cell", { name: "life", exact: true })).toBeVisible();
  expect(config()).toContain("        - life\n");
  expect(config()).toContain("  - name: life\n");
  expect(config()).toContain("    plan:\n      model: opus\n");

  await page.getByRole("button", { name: "Edit life", exact: true }).click();
  await page.getByLabel("Branch tasks start from").fill("develop");
  await page.getByLabel("Alias").fill("lf");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByRole("cell", { name: "lf", exact: true })).toBeVisible();
  expect(config()).toContain("    baseBranch: develop\n");
  expect(config()).toContain("    alias: lf\n");

  await page.getByRole("button", { name: "Remove life", exact: true }).click();
  await page.getByRole("button", { name: "Remove", exact: true }).click();
  await expect(page.getByRole("cell", { name: "life", exact: true })).toHaveCount(0);
  expect(config()).not.toContain("life");
});
