import { expect, test } from "@playwright/test";
import { API, KEYCLOAK, asUser } from "./support";

/** Roadmap 2.2: a validation rule and a calculated attribute, written as expressions, at work in the UI. */
test("a validation rule refuses an object until it's met, and a formula fills in a value", async ({ browser, request }) => {
  const suffix = Date.now().toString(36);
  const token = await request.post(`${KEYCLOAK}/protocol/openid-connect/token`, {
    form: { grant_type: "password", client_id: "plm-web", username: "alice", password: "alice" },
  });
  const { access_token } = (await token.json()) as { access_token: string };
  const headers = { Authorization: `Bearer ${access_token}` };
  const post = async (path: string, data: unknown) => {
    const r = await request.post(`${API}/api/v1${path}`, { headers, data });
    expect(r.ok(), `${path}: ${await r.text()}`).toBeTruthy();
    return r.json();
  };

  // ---- set up by API: a part subtype with a rule and a formula, in a new product ----
  const part = ((await (await request.get(`${API}/api/v1/types`, { headers })).json()) as { id: string; name: string }[]).find(
    (t) => t.name === "Part",
  )!;
  const kit = await post("/types", { parentId: part.id, name: `Kit${suffix}`, displayName: `Kit ${suffix}` });
  await post(`/types/${kit.id}/attributes`, { name: "quantity", dataType: "INTEGER", labels: { en: "Quantity" } });
  await post(`/types/${kit.id}/attributes`, {
    name: "doubled",
    dataType: "INTEGER",
    labels: { en: "Doubled" },
    formula: "object.attributes.quantity * 2",
  });
  await post(`/types/${kit.id}/validation-rules`, {
    name: "Positive quantity",
    condition: "object.attributes.quantity != null && object.attributes.quantity > 0",
    message: "Enter a quantity above zero",
    attribute: "quantity",
  });
  const site = await (await request.get(`${API}/api/v1/contexts/site`, { headers })).json();
  const org = await post("/contexts", { parentId: site.id, kind: "ORGANIZATION", name: `Rules Inc ${suffix}` });
  const product = await post("/contexts", { parentId: org.id, kind: "PRODUCT", name: "Rule product" });

  // ---- in the UI ----
  const alice = await asUser(browser, "alice");
  await alice.goto(`/contexts/${product.id}`);
  await alice.getByRole("button", { name: "New part" }).click();
  const dialog = alice.getByRole("dialog");
  await dialog.getByLabel("Type").selectOption({ label: `Kit ${suffix}` });
  await dialog.getByLabel("Name", { exact: true }).fill("Kit A");
  await expect(dialog.getByLabel("Doubled")).toHaveText(/Computed when saved/);
  await dialog.getByLabel("Quantity").fill("0");
  await dialog.getByRole("button", { name: "Create" }).click();
  await expect(dialog.getByText("Enter a quantity above zero").first()).toBeVisible();

  await dialog.getByLabel("Quantity").fill("21");
  await dialog.getByRole("button", { name: "Create" }).click();
  await expect(alice.getByTestId("version")).toHaveText("A.1");
  await expect(alice.getByText("42", { exact: true })).toBeVisible();
});
