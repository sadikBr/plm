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

/** Roadmap 4.13, 4.9, 4.10: a structure is imported from a file, compared with what it was, and baselined. */
test("a structure is imported from a file, compared with its earlier version and baselined", async ({ browser, request }) => {
  const token = await request.post(`${KEYCLOAK}/protocol/openid-connect/token`, {
    form: { grant_type: "password", client_id: "plm-web", username: "alice", password: "alice" },
  });
  const headers = { Authorization: `Bearer ${((await token.json()) as { access_token: string }).access_token}` };
  const post = async (path: string, data?: unknown) => {
    const r = await request.post(`${API}/api/v1${path}`, { headers, data });
    expect(r.ok(), `${path}: ${await r.text()}`).toBeTruthy();
    return r.json();
  };
  const word = `i${Date.now().toString(36)}`;
  const site = await (await request.get(`${API}/api/v1/contexts/site`, { headers })).json();
  const org = await post("/contexts", { parentId: site.id, kind: "ORGANIZATION", name: `Import ${word}` });
  const product = await post("/contexts", { parentId: org.id, kind: "PRODUCT", name: "Rover" });
  const top = await post("/objects", { typeId: PART, contextId: product.id, name: `Gearbox ${word}` });
  const bolt = await post("/objects", { typeId: PART, contextId: product.id, name: `Bolt ${word}` });
  // A first version using the bolt alone, to compare with.
  await post(`/objects/${top.id}/checkout`);
  await post(`/objects/${top.id}/usages`, { childId: bolt.id, quantity: 4 });
  await post(`/objects/${top.id}/checkin`, { comment: "Bolt" });
  const iterations = (await (await request.get(`${API}/api/v1/objects/${top.id}/iterations`, { headers })).json()) as {
    id: string;
    comment?: string;
  }[];
  const first = iterations.find((i) => i.comment === "Bolt")!;

  const alice = await asUser(browser, "alice");
  await alice.goto(`/objects/${top.id}?tab=structure`);
  await alice.getByRole("button", { name: "Import", exact: true }).click();
  const dialog = alice.getByRole("dialog");
  const csv = ["Level;Number;Name;Quantity;Unit", `1;${bolt.number};;6;each`, `1;;Housing ${word};1;each`, `2;;Seal ${word};2;each`].join(
    "\n",
  );
  await dialog.locator("#import-file").setInputFiles({ name: "gearbox.csv", mimeType: "text/csv", buffer: Buffer.from(csv) });
  await expect(dialog.getByRole("status")).toContainText("2 new parts");
  await dialog.getByRole("button", { name: "Import", exact: true }).click();
  const results = alice.getByRole("table", { name: "Results" });
  await expect(results).toContainText("Structure set", { timeout: 30_000 });
  await expect(results).not.toContainText("not changed");
  const tree = alice.getByRole("table", { name: "Structure" });
  await expect(tree).toContainText(`Housing ${word}`);

  // Compared with the first version: the bolt's quantity changed, the housing (and its seal) added.
  await alice.goto(`/structure-compare?left=${top.id}&leftIteration=${first.id}&right=${top.id}`);
  const differences = alice.getByRole("table", { name: "Differences" });
  await expect(differences.getByRole("row", { name: new RegExp(`Housing ${word}`) })).toContainText("Added");
  await expect(differences.getByRole("row", { name: new RegExp(`Bolt ${word}`) })).toContainText("Changed");

  // Baselined: the gearbox and everything under it.
  await alice.goto(`/objects/${top.id}?tab=structure`);
  await alice.getByRole("button", { name: "Collect…" }).click();
  await alice.getByRole("dialog").getByLabel("New baseline name").fill(`Gearbox ${word} as imported`);
  await alice
    .getByRole("dialog")
    .getByRole("button", { name: /Add \d+ to the baseline/ })
    .click();
  const members = alice.getByRole("table", { name: "Baseline members" });
  await expect(members).toContainText(`Seal ${word}`);
  await expect(members).toContainText(`Bolt ${word}`);
});
