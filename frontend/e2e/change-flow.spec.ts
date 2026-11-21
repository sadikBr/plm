import { expect, test, type Page } from "@playwright/test";
import { ADMIN, API, KEYCLOAK, asUser, passwordOf } from "./support";

/**
 * Phase 5 exit: a problem report goes through a change request (approved by the change board,
 * signed) and a change notice to a released part; the part can't be released any other way.
 */
test("a problem report becomes a released part through a signed change", async ({ browser, request }) => {
  test.setTimeout(180_000);
  const suffix = Date.now().toString(36);
  const tokenOf = async (user: string) => {
    const r = await request.post(`${KEYCLOAK}/protocol/openid-connect/token`, {
      form: { grant_type: "password", client_id: "plm-web", username: user, password: passwordOf(user) },
    });
    return ((await r.json()) as { access_token: string }).access_token;
  };
  const headers = { Authorization: `Bearer ${await tokenOf(ADMIN)}` };
  // bob is in the directory once he has signed in.
  await request.get(`${API}/api/v1/me`, { headers: { Authorization: `Bearer ${await tokenOf("bob")}` } });
  const post = async (path: string, data: unknown) => {
    const r = await request.post(`${API}/api/v1${path}`, { headers, data });
    expect(r.ok(), `${path}: ${await r.text()}`).toBeTruthy();
    return r.json();
  };

  // ---- set up by API: a product where bob is a manager (the change board), and a part ----
  const site = await (await request.get(`${API}/api/v1/contexts/site`, { headers })).json();
  const org = await post("/contexts", { parentId: site.id, kind: "ORGANIZATION", name: `Change Co ${suffix}` });
  const product = await post("/contexts", { parentId: org.id, kind: "PRODUCT", name: `Rover ${suffix}` });
  const bobId = ((await (await request.get(`${API}/api/v1/principals?username=bob`, { headers })).json()) as { id: string }).id;
  await request.put(`${API}/api/v1/contexts/${product.id}/members/${bobId}`, { headers, data: { role: "MANAGER" } });
  const part = await post("/objects", {
    typeId: "00000000-0000-7000-8000-000000000102",
    contextId: product.id,
    name: `Bracket ${suffix}`,
    attributes: { material: "AL6061" },
  });

  const admin = await asUser(browser, ADMIN);
  const setState = async (page: Page, to: string) => {
    await expect(page.getByRole("dialog")).toHaveCount(0);
    await page.locator(".toolbar").getByRole("button", { name: "Set state" }).click();
    await page.getByRole("menuitem", { name: to, exact: true }).click();
    await page.getByRole("dialog").getByRole("button", { name: "Set state" }).click();
    await expect(page.locator(".title-row .state")).toHaveText(to);
  };
  const pick = async (page: Page, input: string, text: string) => {
    await page.locator(input).fill(text);
    await page
      .getByRole("option", { name: new RegExp(text) })
      .first()
      .click();
  };

  // ---- the gate: the part can't simply be released ----
  await admin.goto(`/objects/${part.id}`);
  await setState(admin, "Under Review");
  await admin.locator(".toolbar").getByRole("button", { name: "Set state" }).click();
  await admin.getByRole("menuitem", { name: "Released", exact: true }).click();
  await expect(admin.getByRole("dialog").getByText("Released through a change")).toBeVisible();
  await expect(admin.getByRole("dialog").getByRole("button", { name: "Set state" })).toBeDisabled();
  await admin.getByRole("dialog").getByRole("button", { name: "Cancel" }).click();

  // ---- admin reports the problem, and asks for a change ----
  await admin.goto("/changes");
  await admin.getByRole("button", { name: "Problem report" }).click();
  const dialog = admin.getByRole("dialog");
  await dialog.getByLabel("Name").fill(`Bracket cracks ${suffix}`);
  await dialog.getByLabel("In").selectOption({ label: product.name });
  await dialog.getByRole("button", { name: "Create" }).click();
  await expect(admin.locator(".title-row .num")).toContainText("PR-");
  await pick(admin, "#add-AFFECTED", part.number);
  await expect(admin.getByRole("cell", { name: new RegExp(part.number) }).first()).toBeVisible();
  const prUrl = admin.url();
  await admin.getByRole("button", { name: "Create a change request" }).click();
  await expect(admin.locator(".title-row .num")).toContainText("CR-");
  const crUrl = admin.url().split("?")[0]!;
  await admin.getByRole("button", { name: "Submit to the change board" }).click();
  await expect(admin.getByText("Change board review").first()).toBeVisible();

  // ---- the board (the product's managers: admin, who made it, and bob) approves, each signing ----
  const bob = await asUser(browser, "bob");
  for (const [page, user] of [
    [bob, "bob"],
    [admin, ADMIN],
  ] as const) {
    await page.goto("/inbox");
    await page.getByRole("table", { name: "My tasks" }).getByText(`Bracket cracks ${suffix}`).first().click();
    await page.getByLabel("Your password, to sign").fill(user);
    await page.getByRole("button", { name: "Approve", exact: true }).click();
    await expect(page.getByText(/Answered Approve/)).toBeVisible();
  }

  // ---- the request is approved: admin makes the change notice, with the part as its result ----
  await admin.goto(`${crUrl}?tab=change`);
  await expect(admin.locator(".title-row .state")).toHaveText("Approved", { timeout: 30_000 });
  await admin.getByRole("button", { name: "Create a change notice" }).click();
  await expect(admin.locator(".title-row .num")).toContainText("CN-");
  await pick(admin, "#add-RESULTING", part.number);
  await expect(admin.getByRole("cell", { name: new RegExp(part.number) }).first()).toBeVisible();
  for (const to of ["Under Review", "Approved", "Implementation", "Resolved"]) await setState(admin, to);

  // ---- the part is released, and the chain is closed ----
  await admin.goto(`/objects/${part.id}`);
  await expect(admin.locator(".title-row .state")).toHaveText("Released");
  await admin.goto(crUrl);
  await expect(admin.locator(".title-row .state")).toHaveText("Resolved");
  await expect(admin.getByRole("table", { name: "Electronic signatures" })).toContainText("Bob Builder");
  await expect(admin.getByRole("table", { name: "Electronic signatures" })).toContainText("Site Administrator");
  await admin.goto(prUrl.split("?")[0]!);
  await expect(admin.locator(".title-row .state")).toHaveText("Resolved");
});
