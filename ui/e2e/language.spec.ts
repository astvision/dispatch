import { expect, test } from "@playwright/test";

test.describe("in Mongolian", () => {
  test.use({ locale: "mn-MN" });

  test("a browser that prefers Mongolian opens in Mongolian, down to a server's refusal", async ({ page }) => {
    await page.goto("/settings");
    await expect(page.getByRole("heading", { name: "Тохиргоо" })).toBeVisible();
    await page.getByLabel("Төлөвлөх хугацааны хязгаар").fill("15");
    await page.getByRole("button", { name: "Хадгалах", exact: true }).click();
    await expect(page.getByText("planTimeout: '15' хугацаа буруу (s, m эсвэл h-тэй тоо бичнэ үү, ж: 90s, 15m, 2h)")).toBeVisible();
  });
});

test("the switch turns the page to Mongolian and remembers it", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("button", { name: "Монгол" }).click();
  await expect(page.getByRole("heading", { name: "Тойм" })).toBeVisible();
  await page.reload();
  await expect(page.getByRole("heading", { name: "Тойм" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Монгол" })).toHaveAttribute("aria-pressed", "true");
});

test("no page scrolls sideways at phone width, in either language", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  for (const language of ["English", "Монгол"]) {
    await page.goto("/");
    await page.getByRole("button", { name: language }).click();
    for (const path of ["/", "/tasks", "/projects", "/people", "/settings", "/logs"]) {
      await page.goto(path);
      await expect(page.locator(".board-page h4.ant-typography")).toBeVisible();
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
      expect(overflow, `${path} in ${language}`).toBeLessThanOrEqual(0);
    }
  }
});
