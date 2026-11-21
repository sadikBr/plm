import { expect, test } from "@playwright/test";
import { ADMIN, asUser, openSite } from "./support";

/** Roadmap 1.12: shared teams and context templates, set up once and reused. */
test("the site administrator reuses a shared team and a product template; bob gets access through the team", async ({ browser }) => {
  const suffix = Date.now().toString(36);
  const admin = await asUser(browser, ADMIN);
  const sections = admin.getByRole("navigation", { name: "Sections" });

  // ---- a shared team with bob in it ----
  await admin.getByRole("link", { name: "Admin", exact: true }).click();
  await sections.getByRole("link", { name: "Shared teams" }).click();
  await admin.getByLabel("New team name").fill(`Reviewers ${suffix}`);
  await admin.getByRole("button", { name: "Create", exact: true }).click();
  await expect(admin.getByRole("heading", { name: `Reviewers ${suffix}` })).toBeVisible();
  const addMember = admin.getByRole("form", { name: "Add a team member" });
  await addMember.getByLabel("Username").fill("bob");
  await addMember.getByLabel("Role").selectOption("VIEWER");
  await addMember.getByRole("button", { name: "Add" }).click();
  await expect(admin.getByRole("table", { name: "Team members" }).getByText("Bob Builder")).toBeVisible();

  // ---- a product set up by hand: the team attached ----
  await openSite(admin);
  await admin.getByRole("button", { name: "New organization" }).click();
  await admin.getByLabel("Name").fill(`Templates Inc ${suffix}`);
  await admin.getByRole("button", { name: "Create" }).click();
  await admin.getByRole("navigation", { name: "Sections" }).getByRole("link", { name: "Products" }).click();
  await admin.getByRole("button", { name: "New product" }).click();
  await admin.getByLabel("Name").fill("Prototype");
  await admin.getByRole("button", { name: "Create" }).click();
  await expect(admin.getByRole("heading", { name: "Prototype" })).toBeVisible();
  await admin.getByRole("link", { name: "Team", exact: true }).click();
  await admin.getByLabel("Attach a shared team").selectOption({ label: `Reviewers ${suffix}` });
  await expect(admin.getByRole("table", { name: "Shared teams" }).getByText(`Reviewers ${suffix}`)).toBeVisible();

  // ---- saved as a template ----
  await admin.getByRole("button", { name: "Save as template" }).click();
  await admin.getByLabel("Template name").fill(`Standard product ${suffix}`);
  await admin.getByRole("button", { name: "Save template" }).click();
  await expect(admin.getByText("Saved.")).toBeVisible();
  await admin.getByRole("button", { name: "Done" }).click();

  // ---- a new product from the template ----
  await admin
    .getByRole("link", { name: `Templates Inc ${suffix}`, exact: true })
    .first()
    .click();
  await admin.getByRole("navigation", { name: "Sections" }).getByRole("link", { name: "Products" }).click();
  await admin.getByRole("button", { name: "New product" }).click();
  await admin.getByLabel("Name").fill("Series");
  await admin
    .getByRole("dialog")
    .getByLabel("Template", { exact: true })
    .selectOption({ label: `Standard product ${suffix}` });
  await expect(admin.getByText(/1 shared team/)).toBeVisible();
  await admin.getByRole("button", { name: "Create" }).click();
  await expect(admin.getByRole("heading", { name: "Series" })).toBeVisible();
  const seriesUrl = admin.url();
  await admin.getByRole("link", { name: "Team", exact: true }).click();
  await expect(admin.getByRole("table", { name: "Shared teams" }).getByText(`Reviewers ${suffix}`)).toBeVisible();

  // ---- bob sees it through the shared team, read-only ----
  const bob = await asUser(browser, "bob");
  await bob.goto(seriesUrl);
  await expect(bob.getByRole("heading", { name: "Series" })).toBeVisible();
  await expect(bob.getByRole("button", { name: "New part" })).toHaveCount(0);
});
