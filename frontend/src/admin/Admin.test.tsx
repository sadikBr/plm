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
    version: 1,
    latest: true,
    transitions: [
      { from: "IN_WORK", to: "UNDER_REVIEW" },
      { from: "UNDER_REVIEW", to: "RELEASED" },
    ],
  };
})();

function api(admin = true, extra: Parameters<typeof fakeApi>[0] = {}) {
  return fakeApi({
    "GET /api/v1/me": () => me(admin ? ALICE : BOB, admin),
    "GET /api/v1/contexts/site": () => ({
      id: "site",
      parentId: null,
      kind: "SITE",
      name: "Site",
      createdAt: "2026-11-20T09:00:00Z",
      createdBy: "system",
    }),
    "GET /api/v1/contexts/:id/children": () => [],
    "GET /api/v1/principals/:id": (_, { id }) => principal(id!),
    "GET /api/v1/types": () => types.map((t) => ({ ...t, description: null, labels: {}, icon: null, instantiable: true })),
    "GET /api/v1/attributes/global": () => [],
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
    "GET /api/v1/numbering-schemes": () => [
      { id: "ns1", name: "Fasteners", prefix: "FST-", padding: 4, suffix: "", nextValue: 7, example: "FST-0007" },
    ],
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
    const calls = api(true, {
      "POST /api/v1/configuration/import": (req) => plan(new URL(req.url).searchParams.get("dryRun") === "false"),
    });
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
    await userEvent.upload(
      await screen.findByLabelText("Configuration file"),
      new File(["not json"], "notes.json", { type: "application/json" }),
    );
    expect(await screen.findByText("notes.json is not a JSON configuration file.")).toBeInTheDocument();
  });

  it("edits a lifecycle as a new version, with entry criteria and a revision series", async () => {
    const calls = api(true, {
      "PUT /api/v1/lifecycles/:id": () => ({ ...basic, id: "v2", version: 2 }),
      "GET /api/v1/lifecycles/:id/versions": () => [basic],
    });
    renderAt("/admin?tab=lifecycles");

    await userEvent.click(await screen.findByRole("button", { name: "Edit" }));
    const form = screen.getByRole("form", { name: "Edit lifecycle" });
    expect(within(form).getByLabelText("Name")).toBeDisabled();
    await userEvent.type(within(form).getByLabelText("Attributes required to enter Released"), "material, mass");
    await userEvent.selectOptions(within(form).getByLabelText("Revision series from Released"), "NUMERIC_FROM_1");
    await userEvent.click(within(form).getByRole("button", { name: "Save new version" }));

    await waitFor(() => {
      const body = posted(calls, "PUT", `/api/v1/lifecycles/${basic.id}`);
      const released = (body?.states as { key: string }[]).find((x) => x.key === "RELEASED");
      expect(released).toMatchObject({ requiredAttributes: ["material", "mass"], revisionScheme: "NUMERIC_FROM_1" });
    });
  });

  it("saves type properties: icon, translations and abstract", async () => {
    const calls = api(true, { "PUT /api/v1/types/:id": () => types[1] });
    renderAt(`/admin?tab=types&type=${PART_TYPE}&view=properties`);

    const form = await screen.findByRole("form", { name: "Type properties" });
    await userEvent.click(within(form).getByTitle("bolt"));
    await userEvent.click(within(form).getByRole("button", { name: "Add a translation" }));
    await userEvent.type(within(form).getByLabelText("Language 1"), "fr");
    await userEvent.type(within(form).getByLabelText("Translation 1"), "Pièce");
    await userEvent.click(within(form).getByLabelText(/Abstract/));
    await userEvent.click(within(form).getByRole("button", { name: "Save" }));

    await waitFor(() =>
      expect(posted(calls, "PUT", `/api/v1/types/${PART_TYPE}`)).toEqual({
        displayName: "Part",
        description: null,
        icon: "bolt",
        instantiable: false,
        labels: { fr: "Pièce" },
      }),
    );
  });

  it("moves, hides and restricts actions in the action registry", async () => {
    const def = (label: string, placement: string, order: number) => ({
      label,
      description: `${label} it`,
      icon: null,
      placement,
      order,
      group: "g",
      types: [],
      states: [],
      requires: null,
      handler: "BUILTIN",
    });
    const action = (id: string, label: string, placement: string, order: number) => ({
      id,
      source: "platform",
      hidden: false,
      declared: def(label, placement, order),
      effective: def(label, placement, order),
      configuration: null,
      configuredAt: null,
      configuredBy: null,
    });
    const registry = [
      action("object.check-out", "Check out", "OBJECT_TOOLBAR", 10),
      action("object.revise", "Revise", "OBJECT_TOOLBAR", 40),
      action("object.copy-link", "Copy link", "OBJECT_MORE", 120),
    ];
    const calls = api(true, {
      "GET /api/v1/actions": () => registry,
      "PUT /api/v1/actions/:id/configuration": (_, { id }) => registry.find((a) => a.id === id),
    });
    renderAt("/admin?tab=actions");

    const toolbar = await screen.findByRole("table", { name: "Object toolbar" });
    expect(
      within(toolbar)
        .getAllByRole("row")
        .slice(1)
        .map((r) => within(r).getByRole("strong").textContent),
    ).toEqual(["Check out", "Revise"]);

    await userEvent.click(within(toolbar).getByRole("button", { name: "Move Revise up" }));
    await waitFor(() => expect(posted(calls, "PUT", "/api/v1/actions/object.revise/configuration")).toMatchObject({ order: 10 }));
    expect(posted(calls, "PUT", "/api/v1/actions/object.check-out/configuration")).toMatchObject({ order: 20 });

    await userEvent.selectOptions(screen.getByLabelText("Where Copy link shows"), "HIDDEN");
    await waitFor(() => expect(posted(calls, "PUT", "/api/v1/actions/object.copy-link/configuration")).toMatchObject({ hidden: true }));

    await userEvent.click(within(toolbar).getByRole("button", { name: "Edit Revise" }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.click(within(dialog).getByRole("checkbox", { name: /Released/ }));
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    await waitFor(() =>
      expect(calls.filter((c) => c.path === "/api/v1/actions/object.revise/configuration").at(-1)?.body).toMatchObject({
        states: ["RELEASED"],
      }),
    );
  });

  it("defines a validation rule with a checked condition", async () => {
    const calls = api(true, {
      "GET /api/v1/types/:id/validation-rules": () => [
        {
          id: "r0",
          typeId: "root",
          name: "Inherited rule",
          condition: "true",
          message: "Never fails",
          attribute: null,
          active: true,
          order: 0,
          inherited: true,
          updatedAt: "2026-11-20T09:00:00Z",
          updatedBy: ALICE,
        },
      ],
      "POST /api/v1/expressions/check": () => ({ valid: true, problems: [] }),
      "POST /api/v1/types/:id/validation-rules": () => ({}),
    });
    renderAt(`/admin?tab=types&type=${PART_TYPE}&view=validation`);

    const table = await screen.findByRole("table", { name: "Validation rules" });
    expect(within(table).getByText("Inherited rule")).toBeInTheDocument();
    expect(within(table).queryByRole("button", { name: "Edit Inherited rule" })).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "New rule" }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.type(within(dialog).getByLabelText("Name"), "Bought parts are described");
    await userEvent.type(within(dialog).getByLabelText("Valid when"), "object.attributes.material != null");
    await userEvent.type(within(dialog).getByLabelText("Message when it isn't"), "Pick a material");
    await userEvent.selectOptions(within(dialog).getByLabelText("Show the message at"), "material");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() =>
      expect(posted(calls, "POST", `/api/v1/types/${PART_TYPE}/validation-rules`)).toEqual({
        name: "Bought parts are described",
        condition: "object.attributes.material != null",
        message: "Pick a material",
        attribute: "material",
        active: true,
        order: 0,
      }),
    );
  });

  it("gives a lifecycle state an entry condition", async () => {
    const calls = api(true, {
      "POST /api/v1/lifecycles": () => ({ ...basic, id: "new" }),
      "POST /api/v1/expressions/check": () => ({ valid: true, problems: [] }),
    });
    renderAt("/admin?tab=lifecycles");

    await screen.findByRole("img", { name: "State diagram" });
    await userEvent.click(screen.getByRole("button", { name: "Copy as new" }));
    const form = screen.getByRole("form", { name: "New lifecycle" });
    await userEvent.click(within(form).getByRole("button", { name: "Entry condition of Released" }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.type(within(dialog).getByLabelText("Enter only when"), "object.attributes.material != null");
    await userEvent.type(within(dialog).getByLabelText("Message when it isn't met"), "Pick a material first");
    await userEvent.click(within(dialog).getByRole("button", { name: "Done" }));
    await userEvent.click(within(form).getByRole("button", { name: "Create lifecycle" }));

    await waitFor(() => {
      const states = posted(calls, "POST", "/api/v1/lifecycles")?.states as { key: string }[];
      expect(states.find((x) => x.key === "RELEASED")).toMatchObject({
        entryCondition: "object.attributes.material != null",
        entryMessage: "Pick a material first",
      });
    });
  });

  it("previews an extension before installing it, with its settings", async () => {
    const manifest = {
      id: "acme.erp-sync",
      name: "ERP sync",
      version: "1.0.0",
      platform: ">=1.0 <2.0",
      permissions: ["ui:actions"],
      config: { erpUrl: { type: "url", label: "ERP address", required: true, options: [] } },
      actions: [
        {
          id: "show-in-erp",
          label: "Show in ERP",
          placement: "object.toolbar",
          order: 60,
          appliesTo: { types: ["Part"], states: ["RELEASED"] },
          handler: { url: "${config.erpUrl}/items/${object.number}" },
        },
      ],
    };
    const preview = {
      manifest,
      mode: "INSTALL",
      installedVersion: null,
      compatible: true,
      platformVersion: "1.0",
      permissionsAdded: ["ui:actions"],
      permissionsRemoved: [],
      actionsAdded: ["show-in-erp"],
      actionsChanged: [],
      actionsRemoved: [],
      seedChanges: [{ kind: "enumeration", key: "erpPlants", action: "CREATE", details: [] }],
      configProblems: ["ERP address is required"],
      canInstall: false,
    };
    const calls = api(true, {
      "GET /api/v1/extensions": () => [],
      "GET /api/v1/platform": () => ({
        contractVersion: "1.0",
        extensionPermissions: { "ui:actions": "Add actions to toolbars and menus" },
      }),
      "POST /api/v1/extensions/preview": () => preview,
      "POST /api/v1/extensions": () => ({ id: "acme.erp-sync" }),
    });
    renderAt("/admin?tab=extensions");

    await userEvent.click(await screen.findByRole("button", { name: "Install or upgrade" }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.type(within(dialog).getByLabelText("Or paste the manifest"), "id: acme.erp-sync");
    await userEvent.click(within(dialog).getByRole("button", { name: "Preview" }));

    expect(await within(dialog).findByText(/Compatible: it supports platform/)).toBeInTheDocument();
    expect(within(dialog).getByText("Add actions to toolbars and menus")).toBeInTheDocument();
    expect(within(dialog).getByRole("table", { name: "Extension actions" })).toHaveTextContent("Show in ERP");
    expect(within(dialog).getByRole("list", { name: "Seed changes" })).toHaveTextContent("erpPlants");
    expect(within(dialog).getByText("Required: ERP address")).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Install" })).toBeDisabled();

    await userEvent.type(within(dialog).getByLabelText("ERP address"), "https://erp.example");
    await userEvent.click(within(dialog).getByRole("button", { name: "Install" }));
    await waitFor(() =>
      expect(posted(calls, "POST", "/api/v1/extensions")).toEqual({
        manifest: "id: acme.erp-sync",
        config: { erpUrl: "https://erp.example" },
      }),
    );
  });

  it("lists the site's and extensions' operation rules, and adds one", async () => {
    const calls = api(true, {
      "GET /api/v1/operation-rules": () => [
        {
          id: "acme.quality-rules.say-why-released",
          name: "Say why it's released",
          operations: ["object.set-state"],
          types: [],
          condition: "event.to == 'RELEASED' && event.comment == null",
          message: "A comment is required to release",
          active: true,
          source: "acme.quality-rules",
          updatedAt: null,
          updatedBy: null,
        },
      ],
      "POST /api/v1/expressions/check": () => ({ valid: true, problems: [] }),
      "POST /api/v1/operation-rules": () => ({}),
    });
    renderAt("/admin?tab=rules");

    const table = await screen.findByRole("table", { name: "Operation rules" });
    expect(within(table).getByText("acme.quality-rules")).toBeInTheDocument();
    expect(within(table).queryByRole("button", { name: /Edit Say why/ })).not.toBeInTheDocument(); // the extension's

    await userEvent.click(screen.getByRole("button", { name: "New rule" }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.type(within(dialog).getByLabelText("Name"), "Describe before checking in");
    await userEvent.type(within(dialog).getByLabelText("Refuse when"), "object.attributes.description == null");
    await userEvent.type(within(dialog).getByLabelText("Message"), "Add a description first");
    await userEvent.click(within(dialog).getByRole("checkbox", { name: "Part" }));
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() =>
      expect(posted(calls, "POST", "/api/v1/operation-rules")).toEqual({
        name: "Describe before checking in",
        operations: ["object.check-in"],
        types: ["Part"],
        condition: "object.attributes.description == null",
        message: "Add a description first",
        active: true,
      }),
    );
  });

  it("subscribes to events, and redelivers a dead delivery", async () => {
    const sub = {
      id: "s1",
      name: "ERP releases",
      events: ["versioning.ObjectStateChanged.v1"],
      filter: "event.to == 'RELEASED'",
      url: "https://erp.example/plm-events",
      maxAttempts: 8,
      active: true,
      source: "site",
      updatedAt: null,
      updatedBy: null,
    };
    const calls = api(true, {
      "GET /api/v1/event-types": () => [
        { type: "versioning.ObjectStateChanged.v1", description: "An object moved to another lifecycle state (from, to)" },
        { type: "versioning.ObjectCreated.v1", description: "An object was created" },
      ],
      "GET /api/v1/event-subscriptions": () => [sub],
      "GET /api/v1/event-subscriptions/health": () => [{ subscriptionId: "s1", delivered: 12, pending: 0, dead: 1 }],
      "GET /api/v1/webhook-deliveries": () => [
        {
          id: "d1",
          subscriptionId: "s1",
          subscriptionName: "ERP releases",
          source: "site",
          eventId: "e1",
          eventType: "versioning.ObjectStateChanged.v1",
          aggregateId: "o1",
          url: sub.url,
          status: "DEAD",
          attempts: 8,
          maxAttempts: 8,
          nextAttemptAt: null,
          lastHttpStatus: 503,
          lastError: "The receiver answered 503",
          durationMs: 40,
          createdAt: "2026-11-20T09:00:00Z",
          deliveredAt: null,
        },
      ],
      "POST /api/v1/webhook-deliveries/:id/redeliver": () => ({}),
      "POST /api/v1/expressions/check": () => ({ valid: true, problems: [] }),
      "POST /api/v1/event-subscriptions": () => ({}),
    });
    renderAt("/admin?tab=webhooks");

    const subs = await screen.findByRole("table", { name: "Event subscriptions" });
    expect(await within(subs).findByText("1 dead")).toBeInTheDocument();
    const log = await screen.findByRole("table", { name: "Deliveries" });
    expect(within(log).getByText("Answered 503")).toBeInTheDocument();
    await userEvent.click(within(log).getByRole("button", { name: "Redeliver" }));
    await waitFor(() => expect(calls.some((c) => c.path === "/api/v1/webhook-deliveries/d1/redeliver")).toBe(true));

    await userEvent.click(screen.getByRole("button", { name: "New subscription" }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.type(within(dialog).getByLabelText("Name"), "New parts");
    await userEvent.type(within(dialog).getByLabelText("Send to"), "https://erp.example/new");
    await userEvent.click(within(dialog).getByRole("checkbox", { name: "An object moved to another lifecycle state (from, to)" }));
    await userEvent.click(within(dialog).getByRole("checkbox", { name: "An object was created" }));
    await userEvent.type(within(dialog).getByLabelText("Signing secret"), "s3cret");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    await waitFor(() =>
      expect(posted(calls, "POST", "/api/v1/event-subscriptions")).toEqual({
        name: "New parts",
        events: ["versioning.ObjectCreated.v1"],
        filter: null,
        url: "https://erp.example/new",
        secret: "s3cret",
        maxAttempts: 8,
        active: true,
      }),
    );
  });

  it("lists the site administrators and warns about more than the built-in one on every tab", async () => {
    const admins = {
      builtInUsername: "plmadmin",
      enforced: false,
      moreThanRecommended: true,
      administrators: [
        {
          id: "p1",
          username: "plmadmin",
          displayName: "Site Administrator",
          kind: "USER",
          builtIn: true,
          lastSeen: "2026-11-21T09:00:00Z",
        },
        { id: "p2", username: "alice", displayName: "Alice Anderson", kind: "USER", builtIn: false, lastSeen: "2026-11-21T10:00:00Z" },
      ],
    };
    api(true, { "GET /api/v1/site-administrators": () => admins });
    renderAt("/admin");

    const notice = await screen.findByText(/This site has 2 site administrators/);
    await userEvent.click(within(notice).getByRole("button", { name: "Review them" }));
    const table = await screen.findByRole("table", { name: "Site administrators" });
    expect(within(table).getByText("Alice Anderson")).toBeInTheDocument();
    expect(within(table).getByText("additional")).toBeInTheDocument();
    expect(screen.getByText(/1 more account has site administration rights/)).toBeInTheDocument();
  });
});
