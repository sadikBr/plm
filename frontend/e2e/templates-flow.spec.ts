import { expect, test } from "@playwright/test";
import { asUser } from "./support";

/** Roadmap 1.12: shared teams and context templates, set up once and reused. */
test("alice reuses a shared team and a product template; bob gets access through the team", async ({ browser }) => {
  const suffix = Date.now().toString(36);
  const alice = await asUser(browser, "alice");
  const sections = alice.getByRole("navigation", { name: "Sections" });

  // ---- a shared team with bob in it ----
  await alice.getByRole("link", { name: "Admin", exact: true }).click();
  await sections.getByRole("link", { name: "Shared teams" }).click();
  await alice.getByLabel("New team name").fill(`Reviewers ${suffix}`);
  await alice.getByRole("button", { name: "Create", exact: true }).click();
  await expect(alice.getByRole("heading", { name: `Reviewers ${suffix}` })).toBeVisible();
  const addMember = alice.getByRole("form", { name: "Add a team member" });
  await addMember.getByLabel("Username").fill("bob");
  await addMember.getByLabel("Role").selectOption("VIEWER");
  await addMember.getByRole("button", { name: "Add" }).click();
  await expect(alice.getByRole("table", { name: "Team members" }).getByText("Bob Builder")).toBeVisible();

  // ---- a product set up by hand: the team attached ----
  await alice.goto("/");
  await alice.getByRole("button", { name: "New organization" }).click();
  await alice.getByLabel("Name").fill(`Templates Inc ${suffix}`);
  await alice.getByRole("button", { name: "Create" }).click();
  await alice.getByRole("navigation", { name: "Sections" }).getByRole("link", { name: "Products" }).click();
  await alice.getByRole("button", { name: "New product" }).click();
  await alice.getByLabel("Name").fill("Prototype");
  await alice.getByRole("button", { name: "Create" }).click();
  await expect(alice.getByRole("heading", { name: "Prototype" })).toBeVisible();
  await alice.getByRole("link", { name: "Team", exact: true }).click();
  await alice.getByLabel("Attach a shared team").selectOption({ label: `Reviewers ${suffix}` });
  await expect(alice.getByRole("table", { name: "Shared teams" }).getByText(`Reviewers ${suffix}`)).toBeVisible();

  // ---- saved as a template ----
  await alice.getByRole("button", { name: "Save as template" }).click();
  await alice.getByLabel("Template name").fill(`Standard product ${suffix}`);
  await alice.getByRole("button", { name: "Save template" }).click();
  await expect(alice.getByText("Saved.")).toBeVisible();
  await alice.getByRole("button", { name: "Done" }).click();

  // ---- a new product from the template ----
  await alice.getByRole("link", { name: `Templates Inc ${suffix}`, exact: true }).first().click();
  await alice.getByRole("navigation", { name: "Sections" }).getByRole("link", { name: "Products" }).click();
  await alice.getByRole("button", { name: "New product" }).click();
  await alice.getByLabel("Name").fill("Series");
  await alice.getByRole("dialog").getByLabel("Template", { exact: true }).selectOption({ label: `Standard product ${suffix}` });
  await expect(alice.getByText(/1 shared team/)).toBeVisible();
  await alice.getByRole("button", { name: "Create" }).click();
  await expect(alice.getByRole("heading", { name: "Series" })).toBeVisible();
  const seriesUrl = alice.url();
  await alice.getByRole("link", { name: "Team", exact: true }).click();
  await expect(alice.getByRole("table", { name: "Shared teams" }).getByText(`Reviewers ${suffix}`)).toBeVisible();

  // ---- bob sees it through the shared team, read-only ----
  const bob = await asUser(browser, "bob");
  await bob.goto(seriesUrl);
  await expect(bob.getByRole("heading", { name: "Series" })).toBeVisible();
  await expect(bob.getByRole("button", { name: "New part" })).toHaveCount(0);
});
