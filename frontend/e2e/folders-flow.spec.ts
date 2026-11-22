import { expect, test } from "@playwright/test";
import { ADMIN, API, KEYCLOAK, asUser, passwordOf } from "./support";

/** Roadmap 3.4: a folder is made, a part is put on the clipboard and pasted (moved) into it. */
test("a part on the clipboard is moved into a new folder", async ({ browser, request }) => {
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
  const org = await post("/contexts", { parentId: site.id, kind: "ORGANIZATION", name: `Folders Inc ${Date.now().toString(36)}` });
  const product = await post("/contexts", { parentId: org.id, kind: "PRODUCT", name: "Rover" });
  const part = await post("/objects", { typeId: "00000000-0000-7000-8000-000000000102", contextId: product.id, name: "Axle" });

  const admin = await asUser(browser, ADMIN);
  await admin.goto(`/objects/${part.id}`);
  await admin.getByRole("button", { name: "More", exact: true }).click();
  await admin.locator('[data-action="object.add-to-clipboard"]').click();
  await expect(admin.getByRole("button", { name: "Clipboard (1)" })).toBeVisible();

  await admin.goto(`/contexts/${product.id}?tab=contents`);
  await admin.getByRole("button", { name: "New folder" }).click();
  await admin.getByRole("dialog").getByLabel("Name").fill("Drivetrain");
  await admin.getByRole("dialog").getByRole("button", { name: "Create" }).click();
  await admin.getByRole("table", { name: "Folders" }).getByText("Drivetrain").click();
  await expect(admin.getByText("No objects in this folder.")).toBeVisible();

  await admin.getByRole("button", { name: "Paste 1 here" }).click();
  await admin.getByRole("menuitem", { name: "Move here" }).click();
  await expect(admin.getByRole("link", { name: part.number })).toBeVisible();
  await expect(admin.getByRole("button", { name: /Clipboard/ })).toHaveCount(0);

  await admin.getByRole("navigation", { name: "Folder" }).getByRole("button", { name: "Rover" }).click();
  await expect(admin.getByRole("table", { name: "Folders" }).getByText("1 object")).toBeVisible();
});
