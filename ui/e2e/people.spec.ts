import { expect, test } from "@playwright/test";
import { config } from "./dispatch";

test("a member is renamed", async ({ page }) => {
  await page.goto("/people");
  await expect(page.getByText(/an admin approves them there/)).toBeVisible();

  await page.getByRole("button", { name: "Rename Ali", exact: true }).click();
  await page.getByLabel("New name").fill("Ali Ba'ba");
  await page.getByRole("button", { name: "Save", exact: true }).click();

  // Adaptation: the admin-toggle button's visible text is now "Make Ali Ba'ba admin" (Task 8's WCAG fix), so a
  // substring match on "Ali Ba'ba" is ambiguous; match the table cell's exact text instead.
  await expect(page.getByText("Ali Ba'ba", { exact: true })).toBeVisible();
  expect(config()).toContain("          name: 'Ali Ba''ba'\n");
});
