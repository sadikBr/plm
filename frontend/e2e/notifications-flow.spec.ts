import { expect, test, type APIRequestContext } from "@playwright/test";
import { API, KEYCLOAK, asUser } from "./support";

async function caller(request: APIRequestContext, user: string) {
  const token = await request.post(`${KEYCLOAK}/protocol/openid-connect/token`, {
    form: { grant_type: "password", client_id: "plm-web", username: user, password: user },
  });
  const headers = { Authorization: `Bearer ${((await token.json()) as { access_token: string }).access_token}` };
  return async (method: "GET" | "POST" | "PUT", path: string, data?: unknown) => {
    const r = await request.fetch(`${API}/api/v1${path}`, { method, headers, data });
    expect(r.ok(), `${path}: ${await r.text()}`).toBeTruthy();
    const text = await r.text();
    return text ? JSON.parse(text) : null;
  };
}

/** Roadmap 3.9: Alice follows and stars a part; Bob changes it; Alice's bell tells her. */
test("following a part brings a notification when someone else changes it", async ({ browser, request }) => {
  const alice = await caller(request, "alice");
  const bob = await caller(request, "bob");
  const site = await alice("GET", "/contexts/site");
  const org = await alice("POST", "/contexts", { parentId: site.id, kind: "ORGANIZATION", name: `Notices ${Date.now().toString(36)}` });
  const product = await alice("POST", "/contexts", { parentId: org.id, kind: "PRODUCT", name: "Rover" });
  const bobId = (await alice("GET", "/principals?username=bob")).id;
  await alice("PUT", `/contexts/${product.id}/members/${bobId}`, { role: "MEMBER" });
  const part = await alice("POST", "/objects", { typeId: "00000000-0000-7000-8000-000000000102", contextId: product.id, name: "Axle" });
  await alice("POST", "/me/notifications/read", { ids: [] });

  const page = await asUser(browser, "alice");
  await page.goto(`/objects/${part.id}`);
  await page.getByRole("button", { name: "Add to favorites" }).click();
  await page.getByRole("button", { name: "Follow" }).click();
  await expect(page.getByRole("button", { name: "Following" })).toBeVisible();
  await expect(page.getByRole("region", { name: "Favorites" }).getByText(`${part.number} Axle`)).toBeVisible();

  await bob("POST", `/objects/${part.id}/checkout`);
  await page.reload();
  const bell = page.getByRole("button", { name: /Notifications, \d+ unread/ });
  await expect(bell).toBeVisible();
  await bell.click();
  await page.getByRole("dialog", { name: "Notifications" }).getByText(`${part.number} Axle was checked out`).click();
  await expect(page).toHaveURL(new RegExp(`/objects/${part.id}`));
  await expect(page.getByRole("button", { name: "Notifications", exact: true })).toBeVisible();
});
