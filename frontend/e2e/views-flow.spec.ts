import { expect, test } from "@playwright/test";
import { API, KEYCLOAK, asUser } from "./support";

/** Roadmap 3.7: an attribute column is added, the view saved, and the table exported to Excel. */
test("a view with an attribute column is saved and exported", async ({ browser, request }) => {
  const token = await request.post(`${KEYCLOAK}/protocol/openid-connect/token`, {
    form: { grant_type: "password", client_id: "plm-web", username: "alice", password: "alice" },
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

  const alice = await asUser(browser, "alice");
  await alice.goto(`/contexts/${product.id}?tab=contents`);
  await alice.getByRole("button", { name: "Columns" }).click();
  await alice
    .getByRole("dialog")
    .getByRole("checkbox", { name: /^Material/ })
    .check();
  await alice.getByRole("dialog").getByRole("button", { name: "Apply" }).click();
  await expect(alice.getByRole("table", { name: "Objects" }).getByText("Titanium")).toBeVisible();

  await alice.getByRole("button", { name: "Save view" }).click();
  await alice.getByRole("dialog").getByLabel("Name").fill(`Materials ${suffix}`);
  await alice.getByRole("dialog").getByRole("button", { name: "Save" }).click();
  await expect(alice.getByRole("button", { name: `Materials ${suffix}` })).toBeVisible();

  const [download] = await Promise.all([alice.waitForEvent("download"), alice.getByRole("button", { name: "Export to Excel" }).click()]);
  expect(download.suggestedFilename()).toBe("Rover.xlsx");
});
