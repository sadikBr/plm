import { expect, test } from "@playwright/test";
import { ADMIN, API, KEYCLOAK, asUser, passwordOf } from "./support";

const PART = "00000000-0000-7000-8000-000000000102";

/** The report builder: a report over a structure, built, previewed, saved and run with a question. */
test("a report built from objects, a link, conditions and totals", async ({ browser, request }) => {
  test.setTimeout(120_000);
  const s = Date.now().toString(36);
  const token = async () => {
    const r = await request.post(`${KEYCLOAK}/protocol/openid-connect/token`, {
      form: { grant_type: "password", client_id: "plm-web", username: ADMIN, password: passwordOf(ADMIN) },
    });
    return ((await r.json()) as { access_token: string }).access_token;
  };
  const headers = { Authorization: `Bearer ${await token()}` };
  const call = async (method: string, path: string, data?: unknown) => {
    const r = await request.fetch(`${API}/api/v1${path}`, { method, headers, data });
    expect(r.ok(), `${method} ${path}: ${await r.text()}`).toBeTruthy();
    return r.status() === 204 ? null : r.json();
  };
  const site = await call("GET", "/contexts/site");
  const org = await call("POST", "/contexts", { parentId: site.id, kind: "ORGANIZATION", name: `Report Co ${s}` });
  const product = await call("POST", "/contexts", { parentId: org.id, kind: "PRODUCT", name: `Rig ${s}` });
  const part = (name: string, mass: number) =>
    call("POST", "/objects", {
      typeId: PART,
      contextId: product.id,
      name,
      number: `RB-${name}-${s}`,
      attributes: { mass: { value: mass, unit: "kg" } },
    });
  const frame = await part("FRAME", 40);
  const wheel = await part("WHEEL", 8);
  const motor = await part("MOTOR", 12);
  await call("POST", `/objects/${frame.id}/checkout`);
  await call("POST", `/objects/${frame.id}/usages`, { childId: wheel.id, quantity: 4 });
  await call("POST", `/objects/${frame.id}/usages`, { childId: motor.id, quantity: 2 });
  await call("POST", `/objects/${frame.id}/checkin`, { comment: "structure" });

  const admin = await asUser(browser, ADMIN);
  await admin.goto("/reports");
  await admin.getByRole("button", { name: "New report", exact: true }).click();
  const dialog = admin.getByRole("dialog");
  await dialog.getByRole("textbox", { name: "Name", exact: true }).fill(`Heavy children ${s}`);

  // Find parts, follow their structure to their children.
  await dialog.getByRole("button", { name: "Follow a link" }).click();
  await expect(dialog.getByRole("combobox", { name: "Follow" })).toHaveValue("uses");

  // Where: the part's number is asked when it runs; children heavier than 10 kg.
  await dialog.getByRole("button", { name: "Condition", exact: true }).click();
  await dialog.getByRole("combobox", { name: "Field", exact: true }).last().selectOption("part|number");
  await dialog.getByRole("checkbox", { name: "Ask when run" }).last().check();
  await dialog.getByRole("textbox", { name: "Question" }).fill("Which assembly?");
  await dialog.getByRole("button", { name: "Condition", exact: true }).click();
  await dialog.getByRole("combobox", { name: "Field", exact: true }).last().selectOption("child|attr:mass");
  await dialog.getByRole("combobox", { name: "Operator", exact: true }).last().selectOption("GT");
  await dialog.getByRole("spinbutton", { name: "Value" }).fill("10");

  // Show: the child and its quantity.
  await dialog.getByRole("combobox", { name: "Column 1", exact: true }).selectOption("child|number");
  await dialog.getByRole("combobox", { name: "Column 2", exact: true }).selectOption("child|link:quantity");
  await dialog.getByRole("button", { name: "Remove column 4" }).click();
  await dialog.getByRole("button", { name: "Remove column 3" }).click();
  await dialog.getByRole("button", { name: "Save" }).click();
  await expect(dialog).toHaveCount(0);

  // Run with an answer: only the motor (12 kg) is heavier than 10 kg.
  await admin.getByRole("textbox", { name: /Which assembly/ }).fill(`RB-FRAME-${s}`);
  await admin.getByRole("button", { name: "Run with these answers" }).click();
  const table = admin.getByRole("table", { name: "Report results" });
  await expect(table.getByRole("link", { name: `RB-MOTOR-${s}` })).toBeVisible();
  await expect(table).toContainText("2");
  await expect(table).not.toContainText(`RB-WHEEL-${s}`);
});
