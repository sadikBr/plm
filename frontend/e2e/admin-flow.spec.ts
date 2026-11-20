import { expect, test } from "@playwright/test";
import { asUser } from "./support";

/**
 * The Phase 1 exit criterion: an admin builds a part subtype with a required enumerated attribute
 * and its own numbering entirely in the UI; then creating one shows the right form and number.
 */
test("alice models a Bolt type in the admin UI, then creates one", async ({ browser }) => {
  const suffix = Date.now().toString(36);
  const alice = await asUser(browser, "alice");
  const sections = alice.getByRole("navigation", { name: "Sections" });
  const prefix = `B${suffix.slice(-3).toUpperCase()}-`;

  // ---- a value list ----
  await alice.getByRole("link", { name: "Admin", exact: true }).click();
  await sections.getByRole("link", { name: "Value lists" }).click();
  await alice.getByRole("button", { name: "New list" }).click();
  const list = alice.getByRole("form", { name: "New value list" });
  await list.getByLabel("Label").fill(`Thread standard ${suffix}`);
  await list.getByLabel("Name").fill(`threadStandard${suffix}`);
  for (const [key, label] of [
    ["metric", "Metric"],
    ["unified", "Unified (UNC/UNF)"],
  ]) {
    await list.getByRole("button", { name: "Add value" }).click();
    const n = await list.getByRole("row").count(); // header + values
    await list.getByLabel(`Key of value ${n - 1}`).fill(key!);
    await list.getByLabel(`Label of value ${n - 1}`).fill(label!);
  }
  await list.getByRole("button", { name: "Create list" }).click();
  await expect(alice.getByRole("list", { name: "Value lists" }).getByRole("button", { name: new RegExp(`Thread standard ${suffix}`) })).toBeVisible();

  // ---- a numbering scheme ----
  await sections.getByRole("link", { name: "Numbering" }).click();
  const scheme = alice.getByRole("form", { name: "New numbering scheme" });
  await scheme.getByLabel("Name").fill(`Bolts ${suffix}`);
  await scheme.getByLabel("Prefix").fill(prefix);
  await scheme.getByLabel("Digits").fill("4");
  await scheme.getByRole("button", { name: "Create scheme" }).click();
  await expect(alice.getByRole("cell", { name: `Bolts ${suffix}` })).toBeVisible();

  // ---- the subtype, its attribute, rules and create form ----
  await sections.getByRole("link", { name: "Types and attributes" }).click();
  await alice.getByRole("list", { name: "Types" }).getByRole("button", { name: "Part", exact: true }).click();
  await alice.getByRole("button", { name: "New subtype" }).click();
  await alice.getByLabel("Display name").fill(`Bolt ${suffix}`);
  await alice.getByRole("button", { name: "Create" }).click();
  await expect(alice.getByRole("heading", { name: `Bolt ${suffix}` })).toBeVisible();

  await alice.getByRole("button", { name: "Add attribute" }).click();
  const attr = alice.getByRole("dialog");
  await attr.getByLabel("Label").fill("Thread standard");
  await attr.getByLabel("Data type").selectOption("ENUMERATION");
  await attr.getByLabel("Value list").selectOption({ label: `Thread standard ${suffix} (2 values)` });
  await attr.getByLabel("Required").check();
  await attr.getByRole("button", { name: "Add attribute" }).click();
  await expect(alice.getByRole("table", { name: "Attributes" }).getByRole("cell", { name: "threadStandard" })).toBeVisible();

  await alice.getByRole("button", { name: "Numbering and lifecycle" }).click();
  await alice.getByLabel("Numbering").selectOption({ label: `Bolts ${suffix} — next ${prefix}0001` });
  await expect(alice.getByText("Set on this type")).toBeVisible();

  await alice.getByRole("button", { name: "Layouts" }).click();
  await alice.getByRole("radiogroup", { name: "Screen" }).getByText("Create form").click();
  await expect(alice.getByRole("radio", { name: /Create form/ })).toBeChecked();
  await alice.getByLabel("New group label").fill("Thread");
  await alice.getByRole("button", { name: "Add group" }).click();
  await alice.getByLabel("Add Thread standard to group").selectOption({ label: "Thread" });
  const threadGroup = alice.getByRole("region", { name: "Group Thread" });
  // Up until it's the first group (its "up" button is then disabled).
  const up = threadGroup.getByRole("button", { name: "Move group up" });
  for (let i = 0; i < 5 && (await up.isEnabled()); i++) await up.click();
  await expect(up).toBeDisabled();
  await alice.getByRole("button", { name: "Save layout" }).click();
  await expect(alice.getByText("This type has its own layout for this screen.")).toBeVisible();

  // ---- using it: a product, then a new Bolt ----
  await alice.goto("/");
  await alice.getByRole("button", { name: "New organization" }).click();
  await alice.getByLabel("Name").fill(`Fasteners Inc ${suffix}`);
  await alice.getByRole("button", { name: "Create" }).click();
  await alice.getByRole("navigation", { name: "Sections" }).getByRole("link", { name: "Products" }).click();
  await alice.getByRole("button", { name: "New product" }).click();
  await alice.getByLabel("Name").fill("Gearbox");
  await alice.getByRole("button", { name: "Create" }).click();
  await expect(alice.getByRole("heading", { name: "Gearbox" })).toBeVisible();

  await alice.getByRole("button", { name: "New part" }).click();
  const create = alice.getByRole("dialog");
  await create.getByLabel("Type").selectOption({ label: `Bolt ${suffix}` });
  await create.getByLabel("Name", { exact: true }).fill("Hex bolt M8x30");
  // The Thread group comes first, and its field is required.
  await expect(create.locator("legend").first()).toHaveText("Thread");
  const thread = create.getByLabel("Thread standard");
  await expect(thread).toHaveAttribute("required", "");
  await thread.selectOption("metric");
  await create.getByRole("button", { name: "Create" }).click();
  await expect(alice.getByRole("heading", { level: 1 })).toContainText(`${prefix}0001`);
});
