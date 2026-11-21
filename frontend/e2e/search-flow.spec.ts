import { expect, test } from "@playwright/test";
import { ADMIN, API, KEYCLOAK, asUser, passwordOf } from "./support";

/** Roadmap 3.6: a word inside a file is found once the object is indexed, with a snippet. */
test("a word in an attached file is found by search", async ({ browser, request }) => {
  const token = await request.post(`${KEYCLOAK}/protocol/openid-connect/token`, {
    form: { grant_type: "password", client_id: "plm-web", username: ADMIN, password: passwordOf(ADMIN) },
  });
  const headers = { Authorization: `Bearer ${((await token.json()) as { access_token: string }).access_token}` };
  const post = async (path: string, data?: unknown) => {
    const r = await request.post(`${API}/api/v1${path}`, { headers, data });
    expect(r.ok(), `${path}: ${await r.text()}`).toBeTruthy();
    return r.json();
  };
  const word = `quux${Date.now().toString(36)}`;
  const site = await (await request.get(`${API}/api/v1/contexts/site`, { headers })).json();
  const org = await post("/contexts", { parentId: site.id, kind: "ORGANIZATION", name: `Search Inc ${word}` });
  const product = await post("/contexts", { parentId: org.id, kind: "PRODUCT", name: "Rover" });
  const doc = await post("/objects", { typeId: "00000000-0000-7000-8000-000000000101", contextId: product.id, name: "Test plan" });
  await post(`/objects/${doc.id}/checkout`);
  const body = Buffer.from(`Step 3: run the ${word} vibration profile for ten minutes.`);
  const ticket = await post("/vault/uploads", { fileName: "plan.txt", contentType: "text/plain", size: body.length });
  expect((await request.put(`${API}${ticket.uploadUrl}`, { data: body })).ok()).toBeTruthy();
  await post(`/objects/${doc.id}/content`, { uploadId: ticket.uploadId, role: "PRIMARY" });
  await post(`/objects/${doc.id}/checkin`, { comment: "Plan" });

  const admin = await asUser(browser, ADMIN);
  await expect(async () => {
    await admin.goto(`/search?q=${word}`);
    await expect(admin.getByRole("list", { name: "Search results" }).getByText("Test plan")).toBeVisible({ timeout: 2000 });
  }).toPass({ timeout: 30_000 });
  await expect(admin.locator("mark", { hasText: word })).toBeVisible();
});
