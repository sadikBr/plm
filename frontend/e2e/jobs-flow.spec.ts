import { expect, test } from "@playwright/test";
import { asUser } from "./support";

/** Roadmap 3.1: an administrator starts a job and follows it to the end in the monitor. */
test("a job started from the monitor runs to the end with its progress and log", async ({ browser }) => {
  const alice = await asUser(browser, "alice");
  await alice.goto("/admin?tab=jobs");
  await alice.getByRole("button", { name: "Start a job" }).click();
  const dialog = alice.getByRole("dialog");
  await dialog.getByLabel("Job").selectOption("system.test");
  await dialog.getByLabel("Payload (JSON)").fill('{ "steps": 3, "stepMillis": 100 }');
  await dialog.getByRole("button", { name: "Start" }).click();
  await expect(dialog).toBeHidden();

  // The job just started is selected: its details follow it to the end.
  const log = alice.getByLabel("Job log");
  await expect(log).toContainText("Started, attempt 1 of 3", { timeout: 15_000 });
  await expect(log).toContainText("Succeeded", { timeout: 15_000 });
  await expect(alice.getByRole("table", { name: "Jobs" }).getByText("Test job: 3 steps").first()).toBeVisible();
});
