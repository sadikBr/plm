import { expect, test } from "@playwright/test";
import { ADMIN, asUser } from "./support";

/** Roadmap 3.8: a report is made in the editor and shows its count and chart. */
test("a report made in the editor shows its count, chart and rows", async ({ browser }) => {
  const name = `Released parts ${Date.now().toString(36)}`;
  const admin = await asUser(browser, ADMIN);
  await admin.goto("/reports");
  await admin.getByRole("button", { name: "Simple report" }).click();
  const dialog = admin.getByRole("dialog");
  await dialog.getByRole("textbox", { name: "Name" }).fill(name);
  await dialog.getByRole("checkbox", { name: "Part", exact: true }).check();
  await dialog.getByLabel("Chart: count by").selectOption("state");
  await dialog.getByRole("button", { name: "Save" }).click();
  await expect(dialog).toBeHidden();

  await expect(admin.getByRole("heading", { name })).toBeVisible();
  await expect(admin.locator(".stat-tile .value")).not.toHaveText("…");
  await expect(admin.locator(".bar-row").first()).toBeVisible();
  await expect(admin.getByRole("table", { name: "Report results" })).toBeVisible();

  await admin.goto("/");
  await expect(admin.getByRole("heading", { name: /Welcome/ })).toBeVisible();
});
