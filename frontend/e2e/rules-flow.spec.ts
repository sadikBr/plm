import { readFileSync } from "node:fs";
import { expect, test } from "@playwright/test";
import { ADMIN, API, KEYCLOAK, asUser, passwordOf } from "./support";

/** Roadmap 2.5 (and the Phase 2 exit): an extension's rule refuses check-in without a description. */
let installed: { id: string; headers: Record<string, string> } | undefined;
let gateOff: Record<string, string> | undefined;
test.afterEach(async ({ request }) => {
  if (installed) await request.delete(`${API}/api/v1/extensions/${installed.id}`, { headers: installed.headers });
  // The release gate (roadmap 5.13) is off for this test, which releases directly: back on.
  if (gateOff) await request.put(`${API}/api/v1/release-gate`, { headers: gateOff, data: { enabled: true, states: ["RELEASED"] } });
});

test("the quality rules refuse a check-in without a description and a release without a reason", async ({ browser, request }) => {
  const suffix = Date.now().toString(36);
  const token = await request.post(`${KEYCLOAK}/protocol/openid-connect/token`, {
    form: { grant_type: "password", client_id: "plm-web", username: ADMIN, password: passwordOf(ADMIN) },
  });
  const headers = { Authorization: `Bearer ${((await token.json()) as { access_token: string }).access_token}` };
  const post = async (path: string, data: unknown) => {
    const r = await request.post(`${API}/api/v1${path}`, { headers, data });
    expect(r.ok(), `${path}: ${await r.text()}`).toBeTruthy();
    return r.json();
  };

  // Released through a change is roadmap 5.13's; here the rule is what's tested, so the gate is off.
  gateOff = headers;
  expect((await request.put(`${API}/api/v1/release-gate`, { headers, data: { enabled: false, states: ["RELEASED"] } })).ok()).toBeTruthy();

  // A type of its own, so the rules don't touch other tests' parts.
  const typeName = `Gear${suffix}`;
  const gear = await post("/types", { parentId: "00000000-0000-7000-8000-000000000102", name: typeName, displayName: `Gear ${suffix}` });
  const id = `acme.quality-${suffix}`;
  const manifest = readFileSync(new URL("../../extensions/samples/quality-rules/plm-extension.yaml", import.meta.url), "utf8")
    .replace("id: acme.quality-rules", `id: ${id}`)
    .replaceAll("types: [Part]", `types: [${typeName}]`)
    .replace("    on: [object.set-state]\n", `    on: [object.set-state]\n    appliesTo:\n      types: [${typeName}]\n`);
  installed = { id, headers };
  await post("/extensions", { manifest });

  const site = await (await request.get(`${API}/api/v1/contexts/site`, { headers })).json();
  const org = await post("/contexts", { parentId: site.id, kind: "ORGANIZATION", name: `Quality Inc ${suffix}` });
  const product = await post("/contexts", { parentId: org.id, kind: "PRODUCT", name: "Gearbox" });
  const part = await post("/objects", { typeId: gear.id, contextId: product.id, name: "Spur gear" });

  const admin = await asUser(browser, ADMIN);
  await admin.goto(`/objects/${part.id}`);
  await admin.getByRole("button", { name: "Check out" }).click();
  await admin.getByRole("button", { name: "Check in" }).click();
  const dialog = admin.getByRole("dialog");
  await dialog.getByRole("button", { name: "Check in" }).click();
  await expect(dialog.getByText("Add a description (5 characters or more) before checking in.")).toBeVisible();
  await dialog.getByRole("button", { name: "Cancel" }).click();

  await admin.getByLabel("Description").fill("A 20-tooth spur gear");
  await admin.getByRole("button", { name: "Save attributes" }).click();
  await admin.getByRole("button", { name: "Check in" }).click();
  await dialog.getByRole("button", { name: "Check in" }).click();
  await expect(admin.getByTestId("version")).toHaveText("A.2");

  // Release: refused without a reason, accepted with one.
  await admin.getByRole("button", { name: "Set state" }).click();
  await admin.getByRole("menuitem", { name: "Under Review" }).click();
  await dialog.getByRole("button", { name: "Set state" }).click();
  await admin.getByRole("button", { name: "Set state" }).first().click();
  await admin.getByRole("menuitem", { name: "Released", exact: true }).click();
  await dialog.getByRole("button", { name: "Set state" }).click();
  await expect(dialog.getByText(/a comment is required to release/)).toBeVisible();
  await dialog.getByLabel("Comment").fill("Approved in DR-9");
  await dialog.getByRole("button", { name: "Set state" }).click();
  await expect(admin.getByText("Released").first()).toBeVisible();
});
