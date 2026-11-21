import { expect, test } from "@playwright/test";
import { ADMIN, API, KEYCLOAK, asUser, passwordOf } from "./support";

/** Roadmap 3.7: an attribute column is added, the view saved, and the table exported to Excel. */
test("a view with an attribute column is saved and exported", async ({ browser, request }) => {
  const token = await request.post(`${KEYCLOAK}/protocol/openid-connect/token`, {
    form: { grant_type: "password", client_id: "plm-web", username: ADMIN, password: passwordOf(ADMIN) },
  });
  const headers = { Authorization: `Bearer ${((await token.json()) as { access_token: string }).access_token}` };
  const post = async (path: string, data: unknown) => {
    const r = await request.post(`${API}/api/v1${path}`, { headers, data });
    expect(r.ok(), `${path}: ${await r.text()}`).toBeTruthy();
    return r.json();
  };
  const suffix = Date.now().toString(36);
  const site = await (await request.get(`${API}/api/v1/contexts/site`, { headers })).json();
  const org = await post("/contexts", { parentId: site.id, kind: "ORGANIZATION", name: `Views Inc ${suffix}` });
  const product = await post("/contexts", { parentId: org.id, kind: "PRODUCT", name: "Rover" });
  await post("/objects", {
    typeId: "00000000-0000-7000-8000-000000000102",
    contextId: product.id,
    name: "Hub",
    attributes: { material: "Titanium" },
  });

  const admin = await asUser(browser, ADMIN);
  await admin.goto(`/contexts/${product.id}?tab=contents`);
  await admin.getByRole("button", { name: "Columns" }).click();
  await admin
    .getByRole("dialog")
    .getByRole("checkbox", { name: /^Material/ })
    .check();
  await admin.getByRole("dialog").getByRole("button", { name: "Apply" }).click();
  await expect(admin.getByRole("table", { name: "Objects" }).getByText("Titanium")).toBeVisible();

  await admin.getByRole("button", { name: "Save view" }).click();
  await admin.getByRole("dialog").getByLabel("Name").fill(`Materials ${suffix}`);
  await admin.getByRole("dialog").getByRole("button", { name: "Save" }).click();
  await expect(admin.getByRole("button", { name: `Materials ${suffix}` })).toBeVisible();

  const [download] = await Promise.all([admin.waitForEvent("download"), admin.getByRole("button", { name: "Export to Excel" }).click()]);
  expect(download.suggestedFilename()).toBe("Rover.xlsx");
});
