import { expect, test } from "@playwright/test";
import { ADMIN, API, KEYCLOAK, asUser, passwordOf } from "./support";

/** Roadmap 3.3: Save As makes a copy; compare shows what changed between two versions. */
test("save as copies a document, and compare shows the changed value", async ({ browser, request }) => {
  const token = await request.post(`${KEYCLOAK}/protocol/openid-connect/token`, {
    form: { grant_type: "password", client_id: "plm-web", username: ADMIN, password: passwordOf(ADMIN) },
  });
  const headers = { Authorization: `Bearer ${((await token.json()) as { access_token: string }).access_token}` };
  const call = async (method: "post" | "patch", path: string, data?: unknown) => {
    const r = await request[method](`${API}/api/v1${path}`, { headers, data });
    expect(r.ok(), `${path}: ${await r.text()}`).toBeTruthy();
    return r.json();
  };
  const site = await (await request.get(`${API}/api/v1/contexts/site`, { headers })).json();
  const org = await call("post", "/contexts", { parentId: site.id, kind: "ORGANIZATION", name: `Docs Inc ${Date.now().toString(36)}` });
  const product = await call("post", "/contexts", { parentId: org.id, kind: "PRODUCT", name: "Rover" });
  const doc = await call("post", "/objects", {
    typeId: "00000000-0000-7000-8000-000000000101",
    contextId: product.id,
    name: "Assembly procedure",
    attributes: { description: "First issue" },
  });
  await call("post", `/objects/${doc.id}/checkout`);
  const wc = await (await request.get(`${API}/api/v1/objects/${doc.id}/working-copy`, { headers })).json();
  await call("patch", `/objects/${doc.id}/working-copy`, { attributes: { ...wc.attributes, description: "Second issue" } });
  await call("post", `/objects/${doc.id}/checkin`, { comment: "Updated" });

  const admin = await asUser(browser, ADMIN);
  await admin.goto(`/objects/${doc.id}`);
  await admin.getByRole("button", { name: "More" }).click();
  await admin.locator('[data-action="object.compare"]').click();
  await admin.getByRole("dialog").getByRole("button", { name: "Compare", exact: true }).click();
  const diff = admin.getByRole("table", { name: "Attribute differences" });
  await expect(diff.getByText("First issue")).toBeVisible();
  await expect(diff.getByText("Second issue")).toBeVisible();

  await admin.goto(`/objects/${doc.id}`);
  await admin.getByRole("button", { name: "More" }).click();
  await admin.locator('[data-action="object.save-as"]').click();
  await admin.getByRole("dialog").getByLabel("Name").fill("Assembly procedure, line 2");
  await admin.getByRole("dialog").getByRole("button", { name: "Save as" }).click();
  await expect(admin.locator(".nm").first()).toHaveText("Assembly procedure, line 2");
  await expect(admin.getByTestId("version")).toHaveText("A.1");
});
