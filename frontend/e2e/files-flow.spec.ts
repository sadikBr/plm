import { expect, test } from "@playwright/test";
import { ADMIN, API, KEYCLOAK, asUser, passwordOf } from "./support";

/** Roadmap 3.2: files go into the working copy and become the object's at check-in. */
test("a file uploaded to the working copy is the object's after check-in, and can be previewed", async ({ browser, request }) => {
  const token = await request.post(`${KEYCLOAK}/protocol/openid-connect/token`, {
    form: { grant_type: "password", client_id: "plm-web", username: ADMIN, password: passwordOf(ADMIN) },
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
  const doc = await post("/objects", {
    typeId: "00000000-0000-7000-8000-000000000101",
    contextId: product.id,
    name: "Bracket assembly instructions",
  });

  const admin = await asUser(browser, ADMIN);
  await admin.goto(`/objects/${doc.id}`);
  await expect(admin.getByText("No primary file.")).toBeVisible();
  await admin.locator('[data-action="object.check-out"]').click();
  await expect(admin.getByText("Your working copy's files")).toBeVisible();
  await admin.locator('input[aria-label="Primary file"]').setInputFiles({
    name: "bracket-notes.txt",
    mimeType: "text/plain",
    buffer: Buffer.from("Bracket: 4 x M6, torque 9 Nm"),
  });
  await expect(admin.getByText("bracket-notes.txt")).toBeVisible();

  await admin.locator('[data-action="object.check-in"]').click();
  await admin.getByRole("dialog").getByRole("button", { name: "Check in" }).click();
  await expect(admin.getByText("Your working copy's files")).toHaveCount(0);
  await expect(admin.getByText("bracket-notes.txt")).toBeVisible();
  await admin.getByRole("button", { name: "Preview", exact: true }).click();
  await expect(admin.getByRole("dialog").getByText("torque 9 Nm")).toBeVisible();
});
