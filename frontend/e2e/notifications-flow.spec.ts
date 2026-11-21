import { expect, test, type APIRequestContext } from "@playwright/test";
import { ADMIN, API, KEYCLOAK, asUser, passwordOf } from "./support";

async function caller(request: APIRequestContext, user: string) {
  const token = await request.post(`${KEYCLOAK}/protocol/openid-connect/token`, {
    form: { grant_type: "password", client_id: "plm-web", username: user, password: passwordOf(user) },
  });
  const headers = { Authorization: `Bearer ${((await token.json()) as { access_token: string }).access_token}` };
  return async (method: "GET" | "POST" | "PUT" | "DELETE", path: string, data?: unknown) => {
    const r = await request.fetch(`${API}/api/v1${path}`, { method, headers, data });
    expect(r.ok(), `${path}: ${await r.text()}`).toBeTruthy();
    const text = await r.text();
    return text ? JSON.parse(text) : null;
  };
}

/** Roadmap 3.9: the administrator follows and stars a part; Bob changes it; the administrator's bell says so. */
test("following a part brings a notification when someone else changes it", async ({ browser, request }) => {
  const admin = await caller(request, ADMIN);
  const bob = await caller(request, "bob");
  const site = await admin("GET", "/contexts/site");
  const org = await admin("POST", "/contexts", { parentId: site.id, kind: "ORGANIZATION", name: `Notices ${Date.now().toString(36)}` });
  const product = await admin("POST", "/contexts", { parentId: org.id, kind: "PRODUCT", name: "Rover" });
  const bobId = (await admin("GET", "/principals?username=bob")).id;
  await admin("PUT", `/contexts/${product.id}/members/${bobId}`, { role: "MEMBER" });
  const part = await admin("POST", "/objects", { typeId: "00000000-0000-7000-8000-000000000102", contextId: product.id, name: "Axle" });
  await admin("POST", "/me/notifications/read", { ids: [] });
  // Earlier runs' Axles would push this one out of the navigator's short favorites list.
  for (const f of (await admin("GET", "/me/favorites")) as { kind: string; targetId: string; label: string }[]) {
    if (f.kind === "OBJECT" && f.label.endsWith(" Axle")) await admin("DELETE", `/me/favorites/OBJECT/${f.targetId}`);
  }

  const page = await asUser(browser, ADMIN);
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
  // Opening it marked it read (other tests' jobs may notify admin meanwhile: check this one).
  await expect
    .poll(async () => {
      const all = (await admin("GET", "/me/notifications?limit=50")) as { title: string; readAt: string | null }[];
      return all.find((n) => n.title === `${part.number} Axle was checked out`)?.readAt ?? null;
    })
    .not.toBeNull();
});
