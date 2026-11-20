import { expect, test, type Browser, type Page } from "@playwright/test";

const KEYCLOAK = process.env.PLM_OIDC_ISSUER ?? "http://localhost:8180/realms/plm";
const API = process.env.PLM_API_URL ?? "http://localhost:8080";

async function signIn(page: Page, user: string) {
  await page.goto("/");
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.locator("#username").fill(user);
  await page.locator("#password").fill(user);
  await page.locator("#kc-login").click();
  await expect(page.getByRole("button", { name: "Sign out" })).toBeVisible();
}

async function asUser(browser: Browser, user: string) {
  const page = await (await browser.newContext()).newPage();
  await signIn(page, user);
  return page;
}

/** Users appear in the directory after their first sign-in; do that for bob and carol via the API. */
test.beforeAll(async ({ request }) => {
  for (const user of ["bob", "carol"]) {
    const token = await request.post(`${KEYCLOAK}/protocol/openid-connect/token`, {
      form: { grant_type: "password", client_id: "plm-web", username: user, password: user },
    });
    expect(token.ok()).toBeTruthy();
    const { access_token } = (await token.json()) as { access_token: string };
    const me = await request.get(`${API}/api/v1/me`, { headers: { Authorization: `Bearer ${access_token}` } });
    expect(me.ok()).toBeTruthy();
  }
});

test("alice sets up a product, bob works on a part, carol sees nothing", async ({ browser }) => {
  const suffix = Date.now().toString(36);

  // ---- alice (site admin): organization, product, team ----
  const alice = await asUser(browser, "alice");
  await expect(alice.getByText("Site admin")).toBeVisible();
  await alice.getByRole("button", { name: "New organization" }).click();
  await alice.getByLabel("Name").fill(`Acme ${suffix}`);
  await alice.getByRole("button", { name: "Create" }).click();
  await expect(alice.getByRole("heading", { name: `Acme ${suffix}` })).toBeVisible();

  await alice.getByRole("button", { name: "New context" }).click();
  await alice.getByLabel("Kind").selectOption("PRODUCT");
  await alice.getByLabel("Name").fill("Drone X1");
  await alice.getByRole("button", { name: "Create" }).click();
  await expect(alice.getByRole("heading", { name: "Drone X1" })).toBeVisible();
  const productUrl = alice.url();

  await alice.getByRole("link", { name: "Team" }).click();
  const addMember = alice.getByRole("form", { name: "Add a member" });
  await addMember.getByLabel("Username").fill("bob");
  await addMember.getByLabel("Role").selectOption("MEMBER");
  await addMember.getByRole("button", { name: "Add" }).click();
  await expect(alice.getByRole("cell", { name: "Bob Builder", exact: true })).toBeVisible();

  // ---- bob (member): create, check out, edit, check in, revise ----
  const bob = await asUser(browser, "bob");
  await bob.goto(productUrl);
  await expect(bob.getByRole("heading", { name: "Drone X1" })).toBeVisible();
  await bob.getByRole("button", { name: "New object" }).click();
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

  // Meanwhile alice can see it's taken, and can't check it out.
  await alice.goto(objectUrl);
  await expect(alice.getByTestId("checked-out")).toHaveText("Checked out by Bob Builder");
  await expect(alice.getByRole("button", { name: "Check out" })).toBeDisabled();

  await bob.getByRole("button", { name: "Check in" }).click();
  const dialog = bob.getByRole("dialog");
  await dialog.getByLabel("Comment").fill("Switch to titanium");
  await dialog.getByRole("button", { name: "Check in" }).click();
  await expect(bob.getByTestId("version")).toHaveText("A.2");
  await expect(bob.getByText("Ti-6Al-4V")).toBeVisible();
  await expect(bob.getByText("250 g")).toBeVisible();
  await expect(bob.getByText("Buy", { exact: true })).toBeVisible();

  await bob.getByRole("button", { name: "Revise" }).click();
  await expect(bob.getByTestId("version")).toHaveText("B.1");
  await bob.getByRole("link", { name: "History" }).click();
  const history = bob.getByRole("table", { name: "Iteration history" });
  await expect(history.getByRole("row")).toHaveCount(4); // header + B.1, A.2, A.1
  await expect(history).toContainText("Switch to titanium");

  // ---- alice reads the audit trail ----
  await alice.goto(objectUrl + "?tab=audit");
  await expect(alice.getByRole("table", { name: "Audit trail" }).getByRole("row")).toHaveCount(6);

  // ---- carol (not on the team) ----
  const carol = await asUser(browser, "carol");
  await expect(carol.getByRole("main").getByText(/not on any team yet/)).toBeVisible();
  await carol.goto(objectUrl);
  await expect(carol.getByRole("heading", { name: "Not found" })).toBeVisible();
});
