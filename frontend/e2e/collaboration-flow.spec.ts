import { expect, test } from "@playwright/test";
import { API, KEYCLOAK, asUser } from "./support";

/** Roadmap 3.10: Alice shares a part with Carol (off the team), who joins its discussion. */
test("a part shared with someone off the team can be read and discussed by them", async ({ browser, request }) => {
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
  const org = await post("/contexts", { parentId: site.id, kind: "ORGANIZATION", name: `Shares ${Date.now().toString(36)}` });
  const product = await post("/contexts", { parentId: org.id, kind: "PRODUCT", name: "Rover" });
  const part = await post("/objects", { typeId: "00000000-0000-7000-8000-000000000102", contextId: product.id, name: "Bracket" });
  const carol = await asUser(browser, "carol");
  await carol.goto(`/objects/${part.id}`);
  await expect(carol.getByText(/does not exist|not found/i).first()).toBeVisible();

  const alice = await asUser(browser, "alice");
  await alice.goto(`/objects/${part.id}`);
  await alice.getByRole("button", { name: "Share" }).click();
  const dialog = alice.getByRole("dialog");
  await dialog.getByLabel("Username").fill("carol");
  await dialog.getByRole("button", { name: "Share" }).click();
  await expect(dialog.getByRole("table", { name: "Shared with" })).toContainText("Carol");
  await dialog.getByRole("button", { name: "Done" }).click();
  await alice
    .getByRole("tab", { name: "Discussion" })
    .or(alice.getByRole("link", { name: "Discussion" }))
    .first()
    .click();
  await alice.getByRole("textbox", { name: "Start a topic" }).fill("@carol does this fit your tooling?");
  await alice.getByRole("button", { name: "Post" }).click();
  await expect(alice.locator(".mention", { hasText: "@carol" })).toBeVisible();

  await carol.goto(`/objects/${part.id}?tab=discussion`);
  await expect(carol.getByText("does this fit your tooling?")).toBeVisible();
  await carol.getByRole("button", { name: "Reply" }).click();
  await carol.getByRole("textbox", { name: "Write a reply" }).fill("Yes, it does.");
  await carol.getByRole("button", { name: "Reply" }).click();
  await expect(carol.getByText("Yes, it does.")).toBeVisible();
  await expect(carol.getByRole("region", { name: "Shared with me" })).toContainText("Bracket");
});
