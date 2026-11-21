import { expect, test } from "@playwright/test";
import { ADMIN, API, KEYCLOAK, asUser, openSite, passwordOf } from "./support";

/** Users appear in the directory after their first sign-in; do that for bob and carol via the API. */
test.beforeAll(async ({ request }) => {
  for (const user of ["bob", "carol"]) {
    const token = await request.post(`${KEYCLOAK}/protocol/openid-connect/token`, {
      form: { grant_type: "password", client_id: "plm-web", username: user, password: passwordOf(user) },
    });
    expect(token.ok()).toBeTruthy();
    const { access_token } = (await token.json()) as { access_token: string };
    const me = await request.get(`${API}/api/v1/me`, { headers: { Authorization: `Bearer ${access_token}` } });
    expect(me.ok()).toBeTruthy();
  }
});

test("the site administrator sets up a product, bob works on a part, carol sees nothing", async ({ browser }) => {
  const suffix = Date.now().toString(36);

  // ---- admin (site admin): organization, product, team ----
  const admin = await asUser(browser, ADMIN);
  await openSite(admin);
  await expect(admin.getByText("Site admin", { exact: true })).toBeVisible();
  await admin.getByRole("button", { name: "New organization" }).click();
  await admin.getByLabel("Name").fill(`Acme ${suffix}`);
  await admin.getByRole("button", { name: "Create" }).click();
  await expect(admin.getByRole("heading", { name: `Acme ${suffix}` })).toBeVisible();

  await admin.getByRole("navigation", { name: "Sections" }).getByRole("link", { name: "Products" }).click();
  await admin.getByRole("button", { name: "New product" }).click();
  await admin.getByLabel("Name").fill("Drone X1");
  await admin.getByRole("button", { name: "Create" }).click();
  await expect(admin.getByRole("heading", { name: "Drone X1" })).toBeVisible();
  const productUrl = admin.url();

  await admin.getByRole("link", { name: "Team", exact: true }).click();
  const addMember = admin.getByRole("form", { name: "Add a member" });
  await addMember.getByLabel("Username").fill("bob");
  await addMember.getByLabel("Role").selectOption("MEMBER");
  await addMember.getByRole("button", { name: "Add" }).click();
  await expect(admin.getByRole("cell", { name: "Bob Builder", exact: true })).toBeVisible();

  // ---- bob (member): create, check out, edit, check in, revise ----
  const bob = await asUser(browser, "bob");
  await bob.goto(productUrl);
  await expect(bob.getByRole("heading", { name: "Drone X1" })).toBeVisible();
  // Not a site admin: the site isn't in his navigator, and opening it sends him to his own work.
  await expect(bob.getByRole("list", { name: "Browse by kind" }).getByRole("link", { name: "Site", exact: true })).toHaveCount(0);
  await bob.getByRole("button", { name: "New part" }).click();
  await bob.getByLabel("Type").selectOption({ label: "Part" });
  await bob.getByLabel("Name", { exact: true }).fill("Motor mount");
  const create = bob.getByRole("dialog");
  await create.getByLabel("Material").fill("AL6061");
  await expect(create.getByLabel("Source")).toHaveValue("make"); // out-of-the-box default
  await bob.getByRole("button", { name: "Create" }).click();
  await expect(bob.getByTestId("version")).toHaveText("A.1");
  const objectUrl = bob.url();

  await bob.getByRole("button", { name: "Check out" }).click();
  await expect(bob.getByTestId("checked-out")).toHaveText("Checked out by you");
  await bob.getByLabel("Material").fill("Ti-6Al-4V");
  await bob.getByLabel("Source").selectOption({ label: "Buy" });
  await bob.getByLabel("Mass", { exact: true }).fill("250");
  await bob.getByLabel("Mass unit").selectOption("g");
  await bob.getByRole("button", { name: "Save attributes" }).click();
  await expect(bob.getByText("Saved.")).toBeVisible();

  // Meanwhile admin can see it's taken, and can't check it out.
  await admin.goto(objectUrl);
  await expect(admin.getByTestId("checked-out")).toHaveText("Checked out by Bob Builder");
  await expect(admin.getByRole("button", { name: "Check out" })).toBeDisabled();

  await bob.getByRole("button", { name: "Check in" }).click();
  const dialog = bob.getByRole("dialog");
  await dialog.getByLabel("Comment").fill("Switch to titanium");
  await dialog.getByRole("button", { name: "Check in" }).click();
  await expect(bob.getByTestId("version")).toHaveText("A.2");
  await expect(bob.getByText("Ti-6Al-4V")).toBeVisible();
  await expect(bob.getByText("250 g")).toBeVisible();
  await expect(bob.getByText("Buy", { exact: true })).toBeVisible();

  // In Work can't be revised; bob (a member) can't promote either.
  await expect(bob.getByRole("button", { name: "Revise" })).toBeDisabled();
  await expect(bob.getByRole("button", { name: "Set state" })).toBeDisabled();

  // ---- admin (site admin) releases it: In Work -> Under Review -> Released ----
  const setState = async (to: string) => {
    await expect(admin.getByRole("dialog")).toHaveCount(0);
    await admin.locator(".toolbar").getByRole("button", { name: "Set state" }).click();
    await admin.getByRole("menuitem", { name: to, exact: true }).click();
    await admin.getByRole("dialog").getByLabel("Comment").fill(`Moved to ${to}`);
    // The release gate (roadmap 5.13): Released comes from a change; an administrator may override.
    const gate = admin.getByRole("dialog").getByLabel(/Override the gate/);
    if (to === "Released") await gate.check();
    await admin.getByRole("dialog").getByRole("button", { name: "Set state" }).click();
    await expect(admin.locator(".title-row .state")).toHaveText(to);
  };
  await admin.goto(objectUrl);
  await setState("Under Review");
  await setState("Released");

  // Released: read-only, now revisable.
  await bob.reload();
  await expect(bob.getByRole("button", { name: "Check out" })).toBeDisabled();
  await bob.getByRole("button", { name: "Revise" }).click();
  await expect(bob.getByTestId("version")).toHaveText("B.1");
  await expect(bob.locator(".title-row .state")).toHaveText("In Work");
  await bob.getByRole("link", { name: "History" }).click();
  const history = bob.getByRole("table", { name: "Iteration history" });
  await expect(history.getByRole("row")).toHaveCount(4); // header + B.1, A.2, A.1
  await expect(history).toContainText("Switch to titanium");
  await expect(history.getByRole("row").nth(2)).toContainText("Released"); // A.2 stays Released

  // ---- admin reads the audit trail ----
  await admin.goto(objectUrl + "?tab=audit");
  await expect(admin.getByRole("table", { name: "Audit trail" }).getByRole("row")).toHaveCount(8); // header + 7 events

  // ---- carol (not on the team) ----
  const carol = await asUser(browser, "carol");
  // Not on this product's team, so neither the product nor its part exist for her.
  await carol.goto(productUrl);
  await expect(carol.getByRole("heading", { name: "Not found" })).toBeVisible();
  await carol.goto(objectUrl);
  await expect(carol.getByRole("heading", { name: "Not found" })).toBeVisible();
});
