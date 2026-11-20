import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { fakeApi } from "../test/fakeApi";
import { ALICE, BOB, PART_TYPE, enumerations, lifecycle, me, partAttributes, partLayout, principal, types, units } from "../test/fixtures";
import { renderAt } from "../test/render";

const basic = (() => {
  const lc = lifecycle();
  return {
    id: lc.lifecycleId,
    name: "Basic",
    labels: { en: "Basic" },
    initialState: "IN_WORK",
    system: true,
    states: lc.states,
    transitions: [
      { from: "IN_WORK", to: "UNDER_REVIEW" },
      { from: "UNDER_REVIEW", to: "RELEASED" },
    ],
  };
})();

function api(admin = true, extra: Parameters<typeof fakeApi>[0] = {}) {
  return fakeApi({
    "GET /api/v1/me": () => me(admin ? ALICE : BOB, admin),
    "GET /api/v1/contexts/site": () => ({ id: "site", parentId: null, kind: "SITE", name: "Site", createdAt: "2026-11-20T09:00:00Z", createdBy: "system" }),
    "GET /api/v1/contexts/:id/children": () => [],
    "GET /api/v1/principals/:id": (_, { id }) => principal(id!),
    "GET /api/v1/types": () => types,
    "GET /api/v1/types/:id/attributes": () => partAttributes.map((a) => ({ ...a, typeId: PART_TYPE, inherited: false })),
    "GET /api/v1/types/:id/layouts": () => [],
    "GET /api/v1/types/:id/layouts/:screen": (_, { screen }) => partLayout(screen),
    "GET /api/v1/types/:id/rules": () => ({
      typeId: PART_TYPE,
      numberingSchemeId: null,
      revisionScheme: "ALPHABETIC_ASME",
      allowManualNumber: true,
      inheritedFrom: { revisionScheme: PART_TYPE, allowManualNumber: PART_TYPE },
    }),
    "GET /api/v1/types/:id/lifecycle": () => basic,
    "GET /api/v1/lifecycles": () => [basic],
    "GET /api/v1/numbering-schemes": () => [{ id: "ns1", name: "Fasteners", prefix: "FST-", padding: 4, suffix: "", nextValue: 7, example: "FST-0007" }],
    "GET /api/v1/enumerations": () => enumerations,
    "GET /api/v1/units": () => units,
    "GET /api/v1/team-roles": () => [],
    ...extra,
  });
}

const posted = (calls: ReturnType<typeof api>, method: string, path: string) =>
  calls.find((c) => c.method === method && c.path === path)?.body as Record<string, unknown> | undefined;

describe("Administration", () => {
  it("is for site administrators only", async () => {
    api(false);
    renderAt("/admin");
    expect(await screen.findByText("Administration is for site administrators.")).toBeInTheDocument();
  });

  it("defines an attribute, deriving its API name from the label", async () => {
    const calls = api(true, { "POST /api/v1/types/:id/attributes": () => ({}) });
    renderAt(`/admin?tab=types&type=${PART_TYPE}`);

    await userEvent.click(await screen.findByRole("button", { name: "Add attribute" }));
    const dialog = screen.getByRole("dialog");
    await userEvent.type(within(dialog).getByLabelText("Label"), "Surface finish");
    expect(within(dialog).getByLabelText("Name")).toHaveValue("surfaceFinish");
    await userEvent.selectOptions(within(dialog).getByLabelText("Data type"), "ENUMERATION");
    await userEvent.selectOptions(within(dialog).getByLabelText("Value list"), enumerations[0]!.id);
    await userEvent.click(within(dialog).getByLabelText("Required"));
    await userEvent.click(within(dialog).getByRole("button", { name: "Add attribute" }));

    await waitFor(() =>
      expect(posted(calls, "POST", `/api/v1/types/${PART_TYPE}/attributes`)).toMatchObject({
        name: "surfaceFinish",
        labels: { en: "Surface finish" },
        dataType: "ENUMERATION",
        enumerationId: enumerations[0]!.id,
        required: true,
        dimension: null,
      }),
    );
  });

  it("edits a layout: new group, attribute moved into it, saved for one screen", async () => {
    const calls = api(true, { "PUT /api/v1/types/:id/layouts/:screen": () => ({}) });
    renderAt(`/admin?tab=types&type=${PART_TYPE}&view=layouts`);

    await userEvent.click(await screen.findByText("Create form"));
    await userEvent.type(await screen.findByLabelText("New group label"), "Release info");
    await userEvent.click(screen.getByRole("button", { name: "Add group" }));
    await userEvent.selectOptions(screen.getByLabelText("Add Released at to group"), "releaseInfo");
    // The preview follows the draft.
    const preview = screen.getByRole("group", { name: "Release info" });
    expect(within(preview).getByLabelText("Released at")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Save layout" }));

    await waitFor(() => {
      const body = posted(calls, "PUT", `/api/v1/types/${PART_TYPE}/layouts/CREATE`);
      expect(body?.groups).toEqual([
        { key: "general", labels: { en: "General" }, columns: 2, attributes: ["material", "mass", "source"] },
        { key: "structure", labels: { en: "Structure and tracking" }, columns: 1, attributes: ["endItem"] },
        { key: "releaseInfo", labels: { en: "Release info" }, columns: 2, attributes: ["releasedAt"] },
      ]);
    });
  });

  it("sets a rule on the type and keeps the inherited ones inherited", async () => {
    const calls = api(true, { "PUT /api/v1/types/:id/rules": () => ({}) });
    renderAt(`/admin?tab=types&type=${PART_TYPE}&view=rules`);

    await userEvent.selectOptions(await screen.findByLabelText("Numbering"), "ns1");

    await waitFor(() =>
      expect(posted(calls, "PUT", `/api/v1/types/${PART_TYPE}/rules`)).toEqual({
        numberingSchemeId: "ns1",
        revisionScheme: "ALPHABETIC_ASME", // set on this type already
        allowManualNumber: true,
      }),
    );
  });

  it("copies a lifecycle, adds a transition in the matrix and creates it", async () => {
    const calls = api(true, { "POST /api/v1/lifecycles": () => ({ ...basic, id: "new" }) });
    renderAt("/admin?tab=lifecycles");

    expect(await screen.findByRole("img", { name: "State diagram" })).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Copy as new" }));
    const form = screen.getByRole("form", { name: "New lifecycle" });
    await userEvent.click(within(form).getByLabelText("Released to In Work"));
    await userEvent.click(within(form).getByRole("button", { name: "Create lifecycle" }));

    await waitFor(() => {
      const body = posted(calls, "POST", "/api/v1/lifecycles");
      expect(body).toMatchObject({ name: "BasicCopy", initialState: "IN_WORK" });
      expect(body?.transitions).toContainEqual({ from: "RELEASED", to: "IN_WORK" });
      expect((body?.states as unknown[]).length).toBe(5);
    });
  });

  it("imports a configuration file: dry run first, apply only without conflicts", async () => {
    let conflict = true;
    const plan = (applied: boolean) => ({
      applied,
      creates: 1,
      updates: 0,
      unchanged: 1,
      conflicts: conflict ? 1 : 0,
      changes: [
        { kind: "type", key: "Part", action: "UNCHANGED", details: [] },
        { kind: "attribute", key: "Bolt.thread", action: "CREATE", details: [] },
        ...(conflict ? [{ kind: "lifecycle", key: "Quick", action: "CONFLICT", details: ["exists with different states"] }] : []),
      ],
    });
    const calls = api(true, { "POST /api/v1/configuration/import": (req) => plan(new URL(req.url).searchParams.get("dryRun") === "false") });
    renderAt("/admin?tab=configuration");

    const file = new File([JSON.stringify({ format: "plm-config/1", types: [] })], "plm-config.json", { type: "application/json" });
    await userEvent.upload(await screen.findByLabelText("Configuration file"), file);

    const table = await screen.findByRole("table", { name: "Import plan" });
    expect(within(table).getByText("Conflict")).toBeInTheDocument();
    expect(within(table).queryByText("Part")).not.toBeInTheDocument(); // unchanged rows hidden by default
    expect(screen.getByRole("button", { name: "Apply 1 change" })).toBeDisabled();
    expect(calls.filter((c) => c.method === "POST").map((c) => c.path)).toEqual(["/api/v1/configuration/import?dryRun=true"]);

    // The conflict is fixed in the file: choose it again, then apply.
    conflict = false;
    await userEvent.upload(screen.getByLabelText("Configuration file"), file);
    await waitFor(() => expect(screen.getByRole("button", { name: "Apply 1 change" })).toBeEnabled());
    await userEvent.click(screen.getByRole("button", { name: "Apply 1 change" }));

    expect(await screen.findByRole("heading", { name: "Applied" })).toBeInTheDocument();
    expect(calls.filter((c) => c.method === "POST").at(-1)?.path).toBe("/api/v1/configuration/import?dryRun=false");
  });

  it("refuses a file that isn't JSON", async () => {
    api(true);
    renderAt("/admin?tab=configuration");
    await userEvent.upload(await screen.findByLabelText("Configuration file"), new File(["not json"], "notes.json", { type: "application/json" }));
    expect(await screen.findByText("notes.json is not a JSON configuration file.")).toBeInTheDocument();
  });
});
