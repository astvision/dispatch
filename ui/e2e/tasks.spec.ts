import { expect, test } from "@playwright/test";

// The browser tests run dispatch ui alone, so the bot is never running here: the full path with a running bot is
// DeskProxyTest's, against a real desk port.
test("without the bot the tasks page says so in either language, and the rest still works", async ({ page }) => {
  await page.goto("/tasks");
  await expect(page.getByText("The bot is not running: tasks show while it runs.")).toBeVisible();
  await page.getByRole("button", { name: "Монгол" }).click();
  await expect(page.getByText("Бот ажиллахгүй байна: даалгавар бот ажиллаж байхад харагдана.")).toBeVisible();
  await expect(page.getByText(/Таныг хүлээж/)).toHaveCount(0);
  await page.getByRole("menuitem", { name: "Тойм" }).click();
  await expect(page.getByRole("heading", { name: "Тойм" })).toBeVisible();
});
