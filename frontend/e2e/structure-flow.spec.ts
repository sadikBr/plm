import { expect, test } from "@playwright/test";
import { API, KEYCLOAK, asUser } from "./support";

const PART = "00000000-0000-7000-8000-000000000102";

/** Roadmap 4.1: a part's structure is built on its working copy and becomes its own at check-in. */
test("parts are added to an assembly's structure and kept at check-in", async ({ browser, request }) => {
  const token = await request.post(`${KEYCLOAK}/protocol/openid-connect/token`, {
    form: { grant_type: "password", client_id: "plm-web", username: "alice", password: "alice" },
  });
  const headers = { Authorization: `Bearer ${((await token.json()) as { access_token: string }).access_token}` };
  const post = async (path: string, data?: unknown) => {
    const r = await request.post(`${API}/api/v1${path}`, { headers, data });
    expect(r.ok(), `${path}: ${await r.text()}`).toBeTruthy();
    return r.json();
  };
  const word = `s${Date.now().toString(36)}`;
  const site = await (await request.get(`${API}/api/v1/contexts/site`, { headers })).json();
  const org = await post("/contexts", { parentId: site.id, kind: "ORGANIZATION", name: `Structure ${word}` });
  const product = await post("/contexts", { parentId: org.id, kind: "PRODUCT", name: "Rover" });
  const assembly = await post("/objects", { typeId: PART, contextId: product.id, name: "Axle assembly" });
  const shaft = await post("/objects", { typeId: PART, contextId: product.id, name: `Shaft ${word}` });
  await post(`/objects/${assembly.id}/checkout`);

  const alice = await asUser(browser, "alice");
  await alice.goto(`/objects/${assembly.id}?tab=structure`);
  await alice.getByLabel("Part to add").fill(word);
  await alice.getByRole("listbox", { name: "Matching parts" }).getByText(shaft.number).click();
  await alice.getByRole("form", { name: "Add a part" }).getByLabel("Quantity").fill("2");
  await alice.getByRole("button", { name: "Add", exact: true }).click();
  const table = alice.getByRole("table", { name: "Structure" });
  await expect(table.getByRole("link", { name: shaft.number })).toBeVisible();

  await post(`/objects/${assembly.id}/checkin`, { comment: "Shaft" });
  await alice.reload();
  await expect(table.getByRole("row", { name: new RegExp(shaft.number) })).toContainText("2");
  await expect(alice.getByLabel("Part to add")).toHaveCount(0);
});
