import { expect, test } from "@playwright/test";
import { ADMIN, API, KEYCLOAK, asUser, passwordOf } from "./support";

/** Roadmap 8.2: a workspace is made, a document added, checked out, its change uploaded and checked in. */
test("a workspace tracks a document from check-out to check-in", async ({ browser, request }) => {
  const token = await request.post(`${KEYCLOAK}/protocol/openid-connect/token`, {
    form: { grant_type: "password", client_id: "plm-web", username: ADMIN, password: passwordOf(ADMIN) },
  });
  const headers = { Authorization: `Bearer ${((await token.json()) as { access_token: string }).access_token}` };
  const post = async (path: string, data: unknown) => {
    const r = await request.post(`${API}/api/v1${path}`, { headers, data });
    expect(r.ok(), `${path}: ${await r.text()}`).toBeTruthy();
    return r.json();
  };
  const suffix = Date.now().toString(36);
  const site = await (await request.get(`${API}/api/v1/contexts/site`, { headers })).json();
  const org = await post("/contexts", { parentId: site.id, kind: "ORGANIZATION", name: `Desk Inc ${suffix}` });
  const product = await post("/contexts", { parentId: org.id, kind: "PRODUCT", name: `Desk ${suffix}` });
  const doc = await post("/objects", {
    typeId: "00000000-0000-7000-8000-000000000101",
    contextId: product.id,
    name: "Desk spec",
    number: `DESK-${suffix}`,
  });

  const admin = await asUser(browser, ADMIN);
  await admin.goto("/workspaces");
  await admin.getByRole("button", { name: "New workspace" }).first().click();
  const dialog = admin.getByRole("dialog");
  await dialog.getByLabel("Name").fill(`Desk work ${suffix}`);
  await dialog.getByLabel("New files go to").selectOption({ label: `Desk ${suffix}` });
  await dialog.getByRole("button", { name: "Create" }).click();
  await expect(admin.getByRole("heading", { name: `Desk work ${suffix}` })).toBeVisible();

  await admin.getByRole("button", { name: "Add objects" }).click();
  await admin.getByLabel("Find by number or name").fill(`DESK-${suffix}`);
  await admin
    .getByRole("dialog")
    .getByRole("checkbox", { name: new RegExp(`DESK-${suffix}`) })
    .check();
  await admin.getByRole("dialog").getByRole("button", { name: "Add 1" }).click();
  const files = admin.getByRole("table", { name: "Workspace files" });
  await expect(files.getByText("In sync")).toBeVisible();

  // A changed file: uploading it checks the document out and puts the file in its working copy.
  await files.getByLabel(`Changed file for DESK-${suffix}`).setInputFiles({
    name: "spec.txt",
    mimeType: "text/plain",
    buffer: Buffer.from("desk height 740 mm"),
  });
  await expect(files.getByText("Checked out")).toBeVisible();
  await expect(files.getByText("spec.txt")).toBeVisible();

  await admin.getByRole("button", { name: "Check in…" }).click();
  await admin.getByRole("dialog").getByLabel("Comment").fill("Height from the desk study");
  await admin.getByRole("dialog").getByRole("button", { name: "Check in", exact: true }).click();
  await expect(files.getByText("In sync")).toBeVisible();
  await expect(files.getByText("A.2")).toBeVisible();

  await admin.getByRole("button", { name: "Show", exact: true }).click();
  await expect(admin.getByText("Height from the desk study").or(admin.getByText("Checked in")).first()).toBeVisible();
  expect(doc.id).toBeTruthy();
});
