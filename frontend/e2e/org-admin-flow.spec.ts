import { expect, test } from "@playwright/test";
import { asUser } from "./support";

/** Roadmap 1.14: an organization manager runs their own shared teams and templates, without being a site admin. */
test("bob, manager of an organization, sets up its own shared team and product template", async ({ browser }) => {
  const suffix = Date.now().toString(36);

  // ---- alice (site admin) creates the organization and makes bob its manager ----
  const alice = await asUser(browser, "alice");
  await alice.getByRole("button", { name: "New organization" }).click();
  await alice.getByLabel("Name").fill(`Delegated ${suffix}`);
  await alice.getByRole("button", { name: "Create" }).click();
  await expect(alice.getByRole("heading", { name: `Delegated ${suffix}` })).toBeVisible();
  const orgUrl = alice.url();
  await alice.getByRole("link", { name: "Team" }).click();
  const addMember = alice.getByRole("form", { name: "Add a member" });
  await addMember.getByLabel("Username").fill("bob");
  await addMember.getByLabel("Role").selectOption("MANAGER");
  await addMember.getByRole("button", { name: "Add" }).click();
  await expect(alice.getByRole("cell", { name: "Bob Builder", exact: true })).toBeVisible();

  // ---- bob: the organization's own shared team ----
  const bob = await asUser(browser, "bob");
  await expect(bob.getByRole("link", { name: "Admin", exact: true })).toHaveCount(0); // not a site admin
  await bob.goto(orgUrl);
  await bob.getByRole("link", { name: "Shared teams" }).click();
  await bob.getByLabel("New team name").fill(`Delegated QA ${suffix}`);
  await bob.getByRole("button", { name: "Create", exact: true }).click();
  await expect(bob.getByRole("heading", { name: `Delegated QA ${suffix}` })).toBeVisible();
  const teamForm = bob.getByRole("form", { name: "Add a team member" });
  await teamForm.getByLabel("Username").fill("carol");
  await teamForm.getByLabel("Role").selectOption("VIEWER");
  await teamForm.getByRole("button", { name: "Add" }).click();
  await expect(bob.getByRole("table", { name: "Team members" }).getByText("Carol Contractor")).toBeVisible();

  // ---- a product with the team, saved as the organization's template ----
  await bob.getByRole("link", { name: "Contexts" }).click();
  await bob.getByRole("button", { name: "New context" }).click();
  await bob.getByRole("dialog").getByLabel("Kind").selectOption("PRODUCT");
  await bob.getByRole("dialog").getByLabel("Name").fill("Pilot");
  await bob.getByRole("button", { name: "Create" }).click();
  await expect(bob.getByRole("heading", { name: "Pilot" })).toBeVisible();
  await bob.getByRole("link", { name: "Team" }).click();
  await bob.getByLabel("Attach a shared team").selectOption({ label: `Delegated QA ${suffix}` });
  await expect(bob.getByRole("table", { name: "Shared teams" }).getByText(`Delegated QA ${suffix}`)).toBeVisible();
  await bob.getByRole("button", { name: "Save as template" }).click();
  await expect(bob.getByText(/usable only there/)).toBeVisible();
  await bob.getByLabel("Template name").fill(`Delegated product ${suffix}`);
  await bob.getByRole("button", { name: "Save template" }).click();
  await bob.getByRole("button", { name: "Done" }).click();

  // ---- used for the next product; the organization's Templates tab lists it ----
  await bob.goto(orgUrl);
  await bob.getByRole("button", { name: "New context" }).click();
  await bob.getByRole("dialog").getByLabel("Kind").selectOption("PRODUCT");
  await bob.getByRole("dialog").getByLabel("Name").fill("Series");
  await bob.getByRole("dialog").getByLabel("Template", { exact: true }).selectOption({ label: `Delegated product ${suffix}` });
  await bob.getByRole("button", { name: "Create" }).click();
  await expect(bob.getByRole("heading", { name: "Series" })).toBeVisible();
  await bob.getByRole("link", { name: "Team" }).click();
  await expect(bob.getByRole("table", { name: "Shared teams" }).getByText(`Delegated QA ${suffix}`)).toBeVisible();
  await bob.goto(orgUrl + "?tab=templates");
  await expect(bob.getByRole("list", { name: "Context templates" }).getByText(`Delegated product ${suffix}`)).toBeVisible();
});
