import { spawn, type ChildProcess } from "node:child_process";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { expect, test } from "@playwright/test";
import { ADMIN, API, KEYCLOAK, asUser, passwordOf } from "./support";

/** Roadmap 2.4: the ERP sample's webhooks against the mock ERP: a form, the ERP's answer, a download. */
const PORT = 8199;
let erp: ChildProcess;
let installed: { id: string; headers: Record<string, string> } | undefined;

test.beforeAll(async () => {
  const script = fileURLToPath(new URL("../../extensions/samples/erp-sync/mock-erp.mjs", import.meta.url));
  erp = spawn(process.execPath, [script], { env: { ...process.env, PORT: String(PORT), ERP_SECRET: "e2e-key" }, stdio: "pipe" });
  await new Promise<void>((resolve) => erp.stdout!.on("data", (d: Buffer) => d.toString().includes("listening") && resolve()));
});
test.afterAll(() => {
  erp.kill();
});
// Remove the extension, even when the test failed.
test.afterEach(async ({ request }) => {
  if (installed) await request.delete(`${API}/api/v1/extensions/${installed.id}`, { headers: installed.headers });
});

test("bob sends a released part to the ERP with a form, and downloads its cost report", async ({ browser, request }) => {
  const suffix = Date.now().toString(36);
  const id = `acme.erp-${suffix}`;
  const token = await request.post(`${KEYCLOAK}/protocol/openid-connect/token`, {
    form: { grant_type: "password", client_id: "plm-web", username: ADMIN, password: passwordOf(ADMIN) },
  });
  const headers = { Authorization: `Bearer ${((await token.json()) as { access_token: string }).access_token}` };
  const post = async (path: string, data: unknown) => {
    const r = await request.post(`${API}/api/v1${path}`, { headers, data });
    expect(r.ok(), `${path}: ${await r.text()}`).toBeTruthy();
    return r.json();
  };

  // ---- set up by API: the extension, and a released part bob works on ----
  const manifest = readFileSync(new URL("../../extensions/samples/erp-sync/plm-extension-2.0.yaml", import.meta.url), "utf8").replace(
    "id: acme.erp-sync",
    `id: ${id}`,
  );
  installed = { id, headers };
  await post("/extensions", {
    manifest,
    config: { erpUrl: "https://erp.acme.example", erpApiUrl: `http://localhost:${PORT}`, apiKey: "e2e-key" },
  });
  const site = await (await request.get(`${API}/api/v1/contexts/site`, { headers })).json();
  const org = await post("/contexts", { parentId: site.id, kind: "ORGANIZATION", name: `Hooks Inc ${suffix}` });
  const product = await post("/contexts", { parentId: org.id, kind: "PRODUCT", name: "Gripper" });
  const bobId = ((await (await request.get(`${API}/api/v1/principals?username=bob`, { headers })).json()) as { id: string }).id;
  await request.put(`${API}/api/v1/contexts/${product.id}/members/${bobId}`, { headers, data: { role: "MEMBER" } });
  const part = await post("/objects", { typeId: "00000000-0000-7000-8000-000000000102", contextId: product.id, name: "Finger" });
  await post(`/objects/${part.id}/state`, { state: "RELEASED", override: true });

  // ---- bob: Send to ERP ----
  const bob = await asUser(browser, "bob");
  await bob.goto(`/objects/${part.id}`);
  await bob.locator(`[data-action="${id}.send-to-erp"]`).click();
  const dialog = bob.getByRole("dialog");
  await expect(dialog.getByRole("heading")).toHaveText("Send to ERP: Production order");
  await dialog.getByLabel("Plant").selectOption("P100");
  await dialog.getByLabel("Quantity").fill("500");
  await dialog.getByRole("button", { name: "Send to ERP" }).click();
  await expect(bob.getByRole("alert").filter({ hasText: "can't make more than 100" })).toBeVisible(); // the ERP refused

  await bob.locator(`[data-action="${id}.send-to-erp"]`).click();
  await dialog.getByLabel("Plant").selectOption("P200");
  await dialog.getByLabel("Quantity").fill("12");
  await dialog.getByLabel("Urgent").check();
  await dialog.getByRole("button", { name: "Send to ERP" }).click();
  await expect(
    bob.getByRole("status").filter({ hasText: /Production order SO-\d+ created: 12 × .* at P200, scheduled first/ }),
  ).toBeVisible();

  // ---- a file back from the ERP ----
  await bob.getByRole("button", { name: "More" }).click();
  const download = bob.waitForEvent("download");
  await bob.locator(`[data-action="${id}.erp-report"]`).click();
  expect((await download).suggestedFilename()).toBe(`cost-${part.number}.csv`);

  // ---- the log, for the administrator ----
  const calls = await (await request.get(`${API}/api/v1/extensions/${id}/invocations`, { headers })).json();
  expect((calls as { status: string }[]).map((c) => c.status)).toEqual(["OK", "OK", "FAILED"]);
});
