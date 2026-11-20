import { expect, test, type APIRequestContext } from "@playwright/test";
import { API, KEYCLOAK, asUser } from "./support";

/** Roadmap 2.7: an administrator creates a service account, issues a client secret, and it signs in. */
test("a service account's client secret gives a token that reads but can't change", async ({ browser, request }) => {
  const name = `e2e-${Date.now().toString(36)}`;
  const alice = await asUser(browser, "alice");
  await alice.goto("/admin?tab=service-accounts");
  await alice.getByRole("button", { name: "New service account" }).click();
  const dialog = alice.getByRole("dialog");
  await dialog.getByLabel("Name", { exact: true }).fill(name);
  await dialog.getByLabel("Display name").fill(`E2E ${name}`);
  await dialog.getByRole("button", { name: "Save" }).click();
  await expect(dialog).toBeHidden();

  await alice.getByRole("button", { name: `E2E ${name}`, exact: true }).click();
  await alice.getByRole("button", { name: "Issue token" }).click();
  await dialog.getByLabel("Name", { exact: true }).fill("Gateway");
  await dialog.getByText("Client secret: exchanged").click();
  await dialog.getByRole("button", { name: "Issue" }).click();
  const secret = await alice.getByLabel("New secret").innerText();
  expect(secret).toMatch(/^plm_/);

  const accounts = (await (await request.get(`${API}/api/v1/service-accounts`, { headers: await bearer(request) })).json()) as {
    id: string;
    username: string;
  }[];
  const id = accounts.find((a) => a.username === `svc-${name}`)!.id;
  const exchanged = await request.post(`${API}/api/v1/oauth/token`, {
    form: { grant_type: "client_credentials", client_id: id, client_secret: secret },
  });
  expect(exchanged.status()).toBe(200);
  const access = ((await exchanged.json()) as { access_token: string }).access_token;
  const me = await request.get(`${API}/api/v1/me`, { headers: { Authorization: `Bearer ${access}` } });
  expect(((await me.json()) as { user: { displayName: string } }).user.displayName).toBe(`E2E ${name}`);
  const change = await request.post(`${API}/api/v1/types`, { headers: { Authorization: `Bearer ${access}` }, data: {} });
  expect(change.status()).toBe(403);

  // Revoked, the secret no longer exchanges.
  await alice.getByRole("button", { name: "Done" }).click();
  alice.once("dialog", (d) => void d.accept());
  await alice.getByRole("button", { name: "Revoke Gateway" }).click();
  await expect(alice.getByText("revoked")).toBeVisible();
  const again = await request.post(`${API}/api/v1/oauth/token`, {
    form: { grant_type: "client_credentials", client_id: id, client_secret: secret },
  });
  expect(again.status()).toBe(401);
});

async function bearer(request: APIRequestContext) {
  const token = await request.post(`${KEYCLOAK}/protocol/openid-connect/token`, {
    form: { grant_type: "password", client_id: "plm-web", username: "alice", password: "alice" },
  });
  return { Authorization: `Bearer ${((await token.json()) as { access_token: string }).access_token}` };
}
