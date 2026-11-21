import { expect, test } from "@playwright/test";
import { ADMIN, asUser, openSite } from "./support";

/**
 * The Phase 1 exit criterion: an admin builds a part subtype with a required enumerated attribute
 * and its own numbering entirely in the UI; then creating one shows the right form and number.
 */
test("the site administrator models a Bolt type in the admin UI, then creates one", async ({ browser }) => {
  const suffix = Date.now().toString(36);
  const admin = await asUser(browser, ADMIN);
  const sections = admin.getByRole("navigation", { name: "Sections" });
  const prefix = `B${suffix.slice(-3).toUpperCase()}-`;

  // ---- a value list ----
  await admin.getByRole("link", { name: "Admin", exact: true }).click();
  await sections.getByRole("link", { name: "Value lists" }).click();
  await admin.getByRole("button", { name: "New list" }).click();
  const list = admin.getByRole("form", { name: "New value list" });
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
  await expect(
    admin.getByRole("list", { name: "Value lists" }).getByRole("button", { name: new RegExp(`Thread standard ${suffix}`) }),
  ).toBeVisible();

  // ---- a numbering scheme ----
  await sections.getByRole("link", { name: "Numbering" }).click();
  const scheme = admin.getByRole("form", { name: "New numbering scheme" });
  await scheme.getByLabel("Name").fill(`Bolts ${suffix}`);
  await scheme.getByLabel("Prefix").fill(prefix);
  await scheme.getByLabel("Digits").fill("4");
  await scheme.getByRole("button", { name: "Create scheme" }).click();
  await expect(admin.getByRole("cell", { name: `Bolts ${suffix}` })).toBeVisible();

  // ---- the subtype, its attribute, rules and create form ----
  await sections.getByRole("link", { name: "Types and attributes" }).click();
  await admin.getByRole("list", { name: "Types" }).getByRole("button", { name: "Part", exact: true }).click();
  await admin.getByRole("button", { name: "New subtype" }).click();
  await admin.getByLabel("Display name").fill(`Bolt ${suffix}`);
  await admin.getByRole("button", { name: "Create" }).click();
  await expect(admin.getByRole("heading", { name: `Bolt ${suffix}` })).toBeVisible();

  await admin.getByRole("button", { name: "Add attribute" }).click();
  const attr = admin.getByRole("dialog");
  await attr.getByLabel("Label").fill("Thread standard");
  await attr.getByLabel("Data type").selectOption("ENUMERATION");
  await attr.getByLabel("Value list").selectOption({ label: `Thread standard ${suffix} (2 values)` });
  await attr.getByLabel("Required").check();
  await attr.getByRole("button", { name: "Add attribute" }).click();
  await expect(admin.getByRole("table", { name: "Attributes" }).getByRole("cell", { name: "threadStandard" })).toBeVisible();

  await admin.getByRole("button", { name: "Numbering and lifecycle" }).click();
  await admin.getByLabel("Numbering").selectOption({ label: `Bolts ${suffix} — next ${prefix}0001` });
  await expect(admin.getByText("Set on this type")).toBeVisible();

  await admin.getByRole("button", { name: "Layouts" }).click();
  await admin.getByRole("radiogroup", { name: "Screen" }).getByText("Create form").click();
  await expect(admin.getByRole("radio", { name: /Create form/ })).toBeChecked();
  await admin.getByLabel("New group label").fill("Thread");
  await admin.getByRole("button", { name: "Add group" }).click();
  await admin.getByLabel("Add Thread standard to group").selectOption({ label: "Thread" });
  const threadGroup = admin.getByRole("region", { name: "Group Thread" });
  // Up until it's the first group (its "up" button is then disabled).
  const up = threadGroup.getByRole("button", { name: "Move group up" });
  for (let i = 0; i < 5 && (await up.isEnabled()); i++) await up.click();
  await expect(up).toBeDisabled();
  await admin.getByRole("button", { name: "Save layout" }).click();
  await expect(admin.getByText("This type has its own layout for this screen.")).toBeVisible();

  // ---- using it: a product, then a new Bolt ----
  await openSite(admin);
  await admin.getByRole("button", { name: "New organization" }).click();
  await admin.getByLabel("Name").fill(`Fasteners Inc ${suffix}`);
  await admin.getByRole("button", { name: "Create" }).click();
  await admin.getByRole("navigation", { name: "Sections" }).getByRole("link", { name: "Products" }).click();
  await admin.getByRole("button", { name: "New product" }).click();
  await admin.getByLabel("Name").fill("Gearbox");
  await admin.getByRole("button", { name: "Create" }).click();
  await expect(admin.getByRole("heading", { name: "Gearbox" })).toBeVisible();

  await admin.getByRole("button", { name: "New part" }).click();
  const create = admin.getByRole("dialog");
  await create.getByLabel("Type").selectOption({ label: `Bolt ${suffix}` });
  await create.getByLabel("Name", { exact: true }).fill("Hex bolt M8x30");
  // The Thread group comes first, and its field is required.
  await expect(create.locator("legend").first()).toHaveText("Thread");
  const thread = create.getByLabel("Thread standard");
  await expect(thread).toHaveAttribute("required", "");
  await thread.selectOption("metric");
  await create.getByRole("button", { name: "Create" }).click();
  await expect(admin.getByRole("heading", { level: 1 })).toContainText(`${prefix}0001`);
});
