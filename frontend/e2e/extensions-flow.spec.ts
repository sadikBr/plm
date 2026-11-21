import { readFileSync } from "node:fs";
import { expect, test } from "@playwright/test";
import { ADMIN, API, KEYCLOAK, asUser, passwordOf } from "./support";

/** Roadmap 2.3: the sample extension, installed from its manifest in the UI, adds a working action. */
test("the site administrator installs the ERP sample after a preview, and its action opens the ERP for a released part", async ({
  browser,
  request,
}) => {
  const suffix = Date.now().toString(36);
  const id = `acme.erp-${suffix}`;
  const manifest = readFileSync(new URL("../../extensions/samples/erp-sync/plm-extension.yaml", import.meta.url), "utf8").replace(
    "id: acme.erp-sync",
    `id: ${id}`,
  );

  const admin = await asUser(browser, ADMIN);
  await admin.goto("/admin?tab=extensions");
  await admin.getByRole("button", { name: "Install or upgrade" }).click();
  const dialog = admin.getByRole("dialog");
  await dialog.getByLabel("Or paste the manifest").fill(manifest);
  await dialog.getByRole("button", { name: "Preview" }).click();
  await expect(dialog.getByText(/Compatible: it supports platform/)).toBeVisible();
  await expect(dialog.getByRole("table", { name: "Extension actions" })).toContainText("Show in ERP");
  await dialog.getByLabel("ERP address").fill("https://erp.acme.example");
  await dialog.getByRole("button", { name: "Install", exact: true }).click();
  await expect(admin.getByRole("list", { name: "Installed extensions" })).toContainText("ERP sync");

  // A released part, set up through the API.
  const token = await request.post(`${KEYCLOAK}/protocol/openid-connect/token`, {
    form: { grant_type: "password", client_id: "plm-web", username: ADMIN, password: passwordOf(ADMIN) },
  });
  const headers = { Authorization: `Bearer ${((await token.json()) as { access_token: string }).access_token}` };
  const post = async (path: string, data: unknown) => (await request.post(`${API}/api/v1${path}`, { headers, data })).json();
  const site = await (await request.get(`${API}/api/v1/contexts/site`, { headers })).json();
  const org = await post("/contexts", { parentId: site.id, kind: "ORGANIZATION", name: `ERP Inc ${suffix}` });
  const product = await post("/contexts", { parentId: org.id, kind: "PRODUCT", name: "Arm" });
  const part = await post("/objects", { typeId: "00000000-0000-7000-8000-000000000102", contextId: product.id, name: "Elbow" });
  await post(`/objects/${part.id}/state`, { state: "RELEASED", override: true });

  await admin.goto(`/objects/${part.id}`);
  // Other extensions may add a "Show in ERP" too: this one's.
  const show = admin.locator(`[data-action="${id}.show-in-erp"]`);
  await expect(show).toHaveText("Show in ERP");
  await expect(show).toBeEnabled();
  await expect(show).toHaveAttribute("title", `Opens https://erp.acme.example/items/${part.number}?company=ACME-US`);

  // Leave the site as it was.
  expect((await request.delete(`${API}/api/v1/extensions/${id}`, { headers })).ok()).toBeTruthy();
});
