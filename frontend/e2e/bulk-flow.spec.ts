import { expect, test } from "@playwright/test";
import { API, KEYCLOAK, asUser } from "./support";

/** Roadmap 3.5: ticked objects are moved to a folder by one background job. */
test("ticked objects are moved into a folder by a mass operation", async ({ browser, request }) => {
  const token = await request.post(`${KEYCLOAK}/protocol/openid-connect/token`, {
    form: { grant_type: "password", client_id: "plm-web", username: "alice", password: "alice" },
  });
  const headers = { Authorization: `Bearer ${((await token.json()) as { access_token: string }).access_token}` };
  const post = async (path: string, data: unknown) => {
    const r = await request.post(`${API}/api/v1${path}`, { headers, data });
    expect(r.ok(), `${path}: ${await r.text()}`).toBeTruthy();
    return r.json();
  };
  const site = await (await request.get(`${API}/api/v1/contexts/site`, { headers })).json();
  const org = await post("/contexts", { parentId: site.id, kind: "ORGANIZATION", name: `Bulk Inc ${Date.now().toString(36)}` });
  const product = await post("/contexts", { parentId: org.id, kind: "PRODUCT", name: "Rover" });
  await post(`/contexts/${product.id}/folders`, { name: "Chassis" });
  for (const name of ["Frame", "Skid plate", "Bumper"])
    await post("/objects", { typeId: "00000000-0000-7000-8000-000000000102", contextId: product.id, name });

  const alice = await asUser(browser, "alice");
  await alice.goto(`/contexts/${product.id}?tab=contents`);
  await alice.getByRole("checkbox", { name: "Select all" }).check();
  await expect(alice.getByText("3 selected")).toBeVisible();
  await alice.getByRole("button", { name: /Move/ }).click();
  const dialog = alice.getByRole("dialog");
  await dialog.getByRole("listbox", { name: "Folders" }).getByText("Chassis").click();
  await dialog.getByRole("button", { name: "Start" }).click();

  const results = alice.getByRole("table", { name: "Results" });
  await expect(results.getByText("done")).toHaveCount(3, { timeout: 20_000 });
  await expect(alice.getByText("No objects here yet.")).toBeVisible();
  await expect(alice.getByRole("table", { name: "Folders" }).getByText("3 objects")).toBeVisible();
});
