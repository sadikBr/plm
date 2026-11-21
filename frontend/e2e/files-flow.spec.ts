import { expect, test } from "@playwright/test";
import { API, KEYCLOAK, asUser } from "./support";

/** Roadmap 3.2: files go into the working copy and become the object's at check-in. */
test("a file uploaded to the working copy is the object's after check-in, and can be previewed", async ({ browser, request }) => {
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
  const org = await post("/contexts", { parentId: site.id, kind: "ORGANIZATION", name: `Files Inc ${Date.now().toString(36)}` });
  const product = await post("/contexts", { parentId: org.id, kind: "PRODUCT", name: "Rover" });
  const doc = await post("/objects", { typeId: "00000000-0000-7000-8000-000000000101", contextId: product.id, name: "Bracket assembly instructions" });

  const alice = await asUser(browser, "alice");
  await alice.goto(`/objects/${doc.id}`);
  await expect(alice.getByText("No primary file.")).toBeVisible();
  await alice.locator('[data-action="object.check-out"]').click();
  await expect(alice.getByText("Your working copy's files")).toBeVisible();
  await alice.locator('input[aria-label="Primary file"]').setInputFiles({
    name: "bracket-notes.txt",
    mimeType: "text/plain",
    buffer: Buffer.from("Bracket: 4 x M6, torque 9 Nm"),
  });
  await expect(alice.getByText("bracket-notes.txt")).toBeVisible();

  await alice.locator('[data-action="object.check-in"]').click();
  await alice.getByRole("dialog").getByRole("button", { name: "Check in" }).click();
  await expect(alice.getByText("Your working copy's files")).toHaveCount(0);
  await expect(alice.getByText("bracket-notes.txt")).toBeVisible();
  await alice.getByRole("button", { name: "Preview", exact: true }).click();
  await expect(alice.getByRole("dialog").getByText("torque 9 Nm")).toBeVisible();
});
