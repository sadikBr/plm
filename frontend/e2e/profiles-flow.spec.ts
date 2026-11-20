import { expect, test } from "@playwright/test";
import { API, KEYCLOAK, asUser } from "./support";

/** Roadmap 2.8: a profile hides actions and parts of the interface from its members. */
let created: { id: string; headers: Record<string, string> } | undefined;
test.afterEach(async ({ request }) => {
  if (created) await request.delete(`${API}/api/v1/profiles/${created.id}`, { headers: created.headers });
  created = undefined;
});

test("a reviewers profile hides the search bar, a navigator section and authoring actions", async ({ browser, request }) => {
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
  const org = await post("/contexts", { parentId: site.id, kind: "ORGANIZATION", name: `Profiles Inc ${suffix}` });
  const product = await post("/contexts", { parentId: org.id, kind: "PRODUCT", name: "Rover" });
  const bobId = ((await (await request.get(`${API}/api/v1/principals?username=bob`, { headers })).json()) as { id: string }).id;
  await request.put(`${API}/api/v1/contexts/${product.id}/members/${bobId}`, { headers, data: { role: "MEMBER" } });
  const part = await post("/objects", { typeId: "00000000-0000-7000-8000-000000000102", contextId: product.id, name: "Wheel" });

  const bob = await asUser(browser, "bob");
  await bob.goto(`/objects/${part.id}`);
  await expect(bob.locator('[data-action="object.check-out"]')).toBeVisible();
  await expect(bob.getByRole("navigation", { name: "Sections" }).getByRole("link", { name: "History" })).toBeVisible();

  const alice = await asUser(browser, "alice");
  await alice.goto("/admin?tab=profiles");
  await alice.getByRole("button", { name: "New profile" }).click();
  const dialog = alice.getByRole("dialog");
  await dialog.getByLabel("Name", { exact: true }).fill(`Reviewers ${suffix}`);
  await dialog.getByLabel("Username").fill("bob");
  await dialog.getByRole("button", { name: "Add user" }).click();
  await expect(dialog.getByText("Bob Builder")).toBeVisible();
  await dialog.getByLabel("Search bar").check();
  await dialog.getByLabel("Object page: History tab").check();
  await dialog.locator('input[data-action="object.check-out"]').check();
  await dialog.getByRole("button", { name: "Save" }).click();
  await expect(dialog).toBeHidden();
  const profiles = (await (await request.get(`${API}/api/v1/profiles`, { headers })).json()) as { id: string; name: string }[];
  created = { id: profiles.find((p) => p.name === `Reviewers ${suffix}`)!.id, headers };

  await bob.reload();
  await expect(bob.getByRole("button", { name: "More" })).toBeVisible();
  await expect(bob.locator('[data-action="object.check-out"]')).toHaveCount(0);
  await expect(bob.getByRole("navigation", { name: "Sections" }).getByRole("link", { name: "History" })).toHaveCount(0);
  await expect(bob.getByPlaceholder("Search parts and documents by number or name")).toHaveCount(0);
});
