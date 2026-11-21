import { expect, test } from "@playwright/test";
import { asUser } from "./support";

/** Roadmap 3.8: a report is made in the editor and shows its count and chart. */
test("a report made in the editor shows its count, chart and rows", async ({ browser }) => {
  const name = `Released parts ${Date.now().toString(36)}`;
  const alice = await asUser(browser, "alice");
  await alice.goto("/reports");
  await alice.getByRole("button", { name: "New report" }).click();
  const dialog = alice.getByRole("dialog");
  await dialog.getByRole("textbox", { name: "Name" }).fill(name);
  await dialog.getByRole("checkbox", { name: "Part", exact: true }).check();
  await dialog.getByLabel("Chart: count by").selectOption("state");
  await dialog.getByRole("button", { name: "Save" }).click();
  await expect(dialog).toBeHidden();

  await expect(alice.getByRole("heading", { name })).toBeVisible();
  await expect(alice.locator(".stat-tile .value")).not.toHaveText("…");
  await expect(alice.locator(".bar-row").first()).toBeVisible();
  await expect(alice.getByRole("table", { name: "Report results" })).toBeVisible();

  await alice.goto("/");
  await expect(alice.getByRole("heading", { name: /Welcome/ })).toBeVisible();
});
