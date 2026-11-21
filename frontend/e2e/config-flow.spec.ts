import { expect, test, type Page } from "@playwright/test";
import { API, KEYCLOAK, asUser } from "./support";

const PART = "00000000-0000-7000-8000-000000000102";
const CHANGE_NOTICE = "00000000-0000-7000-8000-000000000106";

/**
 * Phase 7 exit: a configurable product with options and rules is configured into a variant whose
 * filtered structure is generated as parts, reusing a variant generated earlier for a sub-assembly;
 * a change notice makes a new revision effective from a serial number, and the structure for serials
 * below and above it differs as planned; a one-off version for one order stays out of the main line;
 * and a part is found by class attributes and its similar parts.
 */
test("configure, generate and reuse variants; effectivity through change; one-offs; classification", async ({ browser, request }) => {
  test.setTimeout(300_000);
  const suffix = Date.now().toString(36);
  const r = await request.post(`${KEYCLOAK}/protocol/openid-connect/token`, {
    form: { grant_type: "password", client_id: "plm-web", username: "alice", password: "alice" },
  });
  const headers = { Authorization: `Bearer ${((await r.json()) as { access_token: string }).access_token}` };
  const call = async (method: "GET" | "POST" | "PUT" | "DELETE", path: string, data?: unknown) => {
    const res = await request.fetch(`${API}/api/v1${path}`, { method, headers, data });
    expect(res.ok(), `${method} ${path}: ${await res.text()}`).toBeTruthy();
    return res.status() === 204 ? null : res.json();
  };

  // ---- set up by API: a product, its option set and rule, and a 150% structure ----
  const site = await call("GET", "/contexts/site");
  const org = await call("POST", "/contexts", { parentId: site.id, kind: "ORGANIZATION", name: `Config Co ${suffix}` });
  const product = await call("POST", "/contexts", { parentId: org.id, kind: "PRODUCT", name: `Rover R3 ${suffix}` });
  const part = (name: string) => call("POST", "/objects", { typeId: PART, contextId: product.id, name, attributes: {} });
  let set = await call("POST", "/option-sets", { contextId: product.id, name: "Rover R3 options" });
  for (const [code, name, required, choices] of [
    [
      "MOTOR",
      "Drive motor",
      true,
      [
        ["STD", "Standard"],
        ["HT", "High torque"],
      ],
    ],
    [
      "BATTERY",
      "Battery",
      true,
      [
        ["S", "Standard"],
        ["L", "Long range"],
      ],
    ],
    [
      "COLOR",
      "Colour",
      false,
      [
        ["RED", "Red"],
        ["SAND", "Sand"],
      ],
    ],
  ] as const) {
    set = await call("POST", `/option-sets/${set.id}/options`, { code, name, required });
    const option = set.options.find((o: { code: string }) => o.code === code);
    for (const [c, n] of choices) set = await call("POST", `/options/${option.id}/choices`, { code: c, name: n, retired: false });
  }
  await call("POST", `/option-sets/${set.id}/rules`, {
    kind: "REQUIRES",
    ifChoice: { option: "MOTOR", choice: "HT" },
    thenChoices: [{ option: "BATTERY", choice: "L" }],
  });
  const build = async (parent: { id: string }, lines: [{ id: string }, string | null][]) => {
    await call("PUT", `/objects/${parent.id}/options`, { optionSetId: set.id });
    await call("POST", `/objects/${parent.id}/checkout`);
    for (const [child, condition] of lines) {
      const line = await call("POST", `/objects/${parent.id}/usages`, { childId: child.id, quantity: 1 });
      if (condition) await call("PUT", `/objects/${parent.id}/usages/${line.id}/condition`, { expression: condition });
    }
    await call("POST", `/objects/${parent.id}/checkin`, {});
  };
  const power = await part("Power unit");
  await build(power, [
    [await part("Controller standard"), "MOTOR=STD"],
    [await part("Controller high current"), "MOTOR=HT"],
    [await part("Harness"), null],
  ]);
  const motor = await part("Drive motor");
  const rover = await part(`Rover R3 ${suffix}`);
  await build(rover, [
    [power, null],
    [motor, null],
    [await part("Pack 2 kWh"), "BATTERY=S"],
    [await part("Pack 4 kWh"), "BATTERY=L"],
    [await part("Panels, red"), "COLOR=RED"],
    [await part("Panels, sand"), "not COLOR=RED"],
  ]);

  const alice = await asUser(browser, "alice");

  // ---- configured: the rules catch a bad choice, the fix is one click; saved and generated ----
  await alice.goto(`/objects/${rover.id}?tab=options`);
  const choices = alice.locator(".configured-choices");
  await choices.getByLabel("Drive motor").selectOption("HT");
  await choices.getByLabel("Battery").selectOption("S");
  await expect(alice.getByRole("list", { name: "Problems" })).toContainText("MOTOR=HT requires one of BATTERY=L");
  await alice.getByRole("button", { name: "Choose BATTERY=L" }).click();
  await expect(alice.locator(".configured-choices .check-ok")).toBeVisible();
  const configured = alice.getByRole("table", { name: "150% structure" });
  await alice.getByLabel("Only what applies").check();
  await expect(configured).toContainText("Pack 4 kWh");
  await expect(configured).not.toContainText("Pack 2 kWh");
  await expect(configured).toContainText("Controller high current");
  await alice.getByLabel("Variant name").fill("Expedition");
  await alice.getByRole("button", { name: "Save as a variant specification" }).click();
  const specs = alice.getByRole("table", { name: "Variant specifications" });
  const generate = async (name: string) => {
    await specs
      .getByRole("row", { name: new RegExp(name) })
      .getByRole("button", { name: "Generate" })
      .click();
    await expect(alice.getByText(`Generated Rover R3 ${suffix} – ${name}`)).toBeVisible();
  };
  await generate("Expedition");
  await expect(alice.getByText("2 made, 0 reused.")).toBeVisible();

  // Another variant with the same motor: its power unit variant is reused.
  await choices.getByLabel("Colour").selectOption("RED");
  await alice.getByLabel("Variant name").fill("Rally");
  await alice.getByRole("button", { name: "Save as a variant specification" }).click();
  await expect(specs.getByRole("row", { name: /Rally/ })).toBeVisible();
  await generate("Rally");
  await expect(alice.getByText("1 made, 1 reused.")).toBeVisible();
  const generated = alice.getByRole("table", { name: "Generated parts" });
  await expect(generated.getByRole("row", { name: /Power unit – HT/ })).toContainText("reused");

  // ---- effectivity through a change notice: motor B from unit 100 ----
  await call("POST", `/objects/${motor.id}/state`, { state: "RELEASED", override: true });
  await alice.goto(`/objects/${rover.id}?tab=effectivity`);
  await alice.getByRole("button", { name: "Make it an end item" }).click();
  await expect(alice.getByText("is an end item")).toBeVisible();
  await call("POST", `/objects/${motor.id}/effectivity`, {
    target: "VERSION",
    revision: "A",
    kind: "SERIAL",
    from: "1",
    endItemId: rover.id,
  });
  await call("POST", `/objects/${motor.id}/revise`);
  const cn = await call("POST", "/objects", {
    typeId: CHANGE_NOTICE,
    contextId: product.id,
    name: `Motor B from unit 100 ${suffix}`,
    attributes: {},
  });
  await call("POST", `/changes/${cn.id}/links`, { targetId: motor.id, role: "RESULTING" });
  await call("POST", `/objects/${motor.id}/effectivity`, {
    target: "VERSION",
    revision: "B",
    kind: "SERIAL",
    from: "100",
    endItemId: rover.id,
    changeId: cn.id,
  });
  await alice.goto(`/objects/${cn.id}?tab=change`);
  await expect(alice.getByRole("table", { name: "Effectivity of the results" })).toContainText("Pending");
  const setState = async (page: Page, to: string) => {
    await expect(page.getByRole("dialog")).toHaveCount(0);
    await page.locator(".toolbar").getByRole("button", { name: "Set state" }).click();
    await page.getByRole("menuitem", { name: to, exact: true }).click();
    await page.getByRole("dialog").getByRole("button", { name: "Set state" }).click();
    await expect(page.locator(".title-row .state")).toHaveText(to);
  };
  for (const to of ["Under Review", "Approved", "Implementation", "Resolved"]) await setState(alice, to);
  await alice.goto(`/objects/${motor.id}?tab=effectivity`);
  const versions = alice.getByRole("table", { name: "Version effectivity" });
  await expect(versions.getByRole("row", { name: /^A/ })).toContainText("1 – 99");
  await expect(versions.getByRole("row", { name: /^B/ })).toContainText("100 –");

  const motorAt = async (serial: string) => {
    await alice.goto(`/objects/${rover.id}?tab=structure`);
    await alice.locator("#spec").selectOption("EFFECTIVITY");
    await alice.getByLabel("Effective by").selectOption("SERIAL");
    await alice.getByLabel("Effective for").fill(serial);
    await alice.getByLabel("Effective for").press("Enter");
    return alice
      .getByRole("table", { name: "Structure" })
      .last()
      .getByRole("row", { name: /Drive motor/ });
  };
  await expect(await motorAt("50")).toContainText("A.1");
  await expect(await motorAt("150")).toContainText("B.1");

  // ---- a one-off of the motor for one order: the main line doesn't move ----
  await alice.goto(`/objects/${motor.id}?tab=history`);
  await alice.getByLabel("One-off for").fill(`Order 4471 ${suffix}`);
  await alice.getByRole("button", { name: /Make a one-off of B\.1/ }).click();
  await expect(alice.locator(".one-off-banner")).toContainText("one-off B.1-1");
  await call("POST", `/objects/${motor.id}/checkin`, { comment: "Special winding for order 4471" });
  await alice.reload();
  await expect(alice.getByRole("table", { name: "One-off versions" })).toContainText(`Order 4471 ${suffix}`);
  expect((await call("GET", `/objects/${motor.id}`)).latest.version).toBe("B.1");
  const latest = await call("GET", `/objects/${rover.id}/structure?spec=LATEST&depth=1`);
  expect(latest.nodes.find((n: { line: { child: { name: string } } }) => n.line.child.name === "Drive motor").line.child.version).toBe(
    "B.1",
  );

  // ---- classification: found by class attributes, and by likeness ----
  const cls = await call("POST", "/classes", { code: `MOT-${suffix}`, name: `Motors ${suffix}` });
  await call("POST", `/classes/${cls.id}/attributes`, {
    key: "power",
    label: "Rated power",
    dataType: "NUMBER",
    unit: "W",
    allowed: [],
    required: true,
  });
  await call("POST", `/classes/${cls.id}/attributes`, {
    key: "cooling",
    label: "Cooling",
    dataType: "ENUM",
    allowed: ["fan", "liquid"],
    required: false,
  });
  const classified: Record<string, string> = {};
  for (const [name, power, cooling] of [
    ["Motor 750 W", 750, "fan"],
    ["Motor 1000 W", 1000, "fan"],
    ["Motor 3000 W", 3000, "liquid"],
  ] as const) {
    const p = await part(name);
    await call("POST", `/objects/${p.id}/checkout`);
    await call("PUT", `/objects/${p.id}/classification`, { classId: cls.id, values: { power, cooling } });
    await call("POST", `/objects/${p.id}/checkin`, {});
    classified[name] = p.id;
  }
  await alice.goto(`/classes?class=${cls.id}`);
  const parts = alice.getByRole("table", { name: "Classified parts" });
  await expect(parts.getByRole("row")).toHaveCount(4);
  await alice.getByLabel("Rated power from").fill("700");
  await alice.getByLabel("Rated power to").fill("1200");
  await expect(parts.getByRole("row")).toHaveCount(3);
  await expect(parts).not.toContainText("Motor 3000 W");
  await alice.goto(`/objects/${classified["Motor 1000 W"]}?tab=classification`);
  const similar = alice.getByRole("table", { name: "Similar parts" });
  await expect(similar.getByRole("row").nth(2)).toContainText("Motor 750 W");
  await expect(similar.getByRole("row").nth(3)).toContainText("Motor 3000 W");
});
