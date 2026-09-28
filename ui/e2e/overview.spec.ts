import { expect, test } from "@playwright/test";

test("without the bot Тойм says so beside the service, in either language, and Tasks offers no task to give", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByText("The bot is not running: start the service to see what it does.")).toBeVisible();
  await page.getByRole("button", { name: "Монгол" }).click();
  await expect(page.getByText("Бот ажиллахгүй байна: юу хийж байгааг харахын тулд сервисийг эхлүүлнэ үү.")).toBeVisible();
  await page.goto("/tasks");
  await expect(page.getByText("Бот ажиллахгүй байна: даалгавар бот ажиллаж байхад харагдана.")).toBeVisible();
  await expect(page.getByRole("button", { name: "Даалгавар өгөх" })).toHaveCount(0);
});
