import { expect, test } from "@playwright/test";
import { config } from "./dispatch";

test("a member is renamed", async ({ page }) => {
  await page.goto("/people");
  await expect(page.getByText(/an admin approves them there/)).toBeVisible();

  await page.getByRole("button", { name: "Rename Ali", exact: true }).click();
  await page.getByLabel("New name").fill("Ali Ba'ba");
  await page.getByRole("button", { name: "Save", exact: true }).click();

  // The name's cell holds exactly the name; the admin switch beside it names the member only in its aria-label.
  await expect(page.getByText("Ali Ba'ba", { exact: true })).toBeVisible();
  expect(config()).toContain("          name: 'Ali Ba''ba'\n");
});
