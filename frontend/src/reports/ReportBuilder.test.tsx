import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { fakeApi } from "../test/fakeApi";
import { ALICE, PART_TYPE, lifecycle, me, types } from "../test/fixtures";
import { renderAt } from "../test/render";

const fields = [
  { key: "number", label: "Number", type: "TEXT", values: [], unit: null },
  { key: "name", label: "Name", type: "TEXT", values: [], unit: null },
  { key: "version", label: "Version", type: "TEXT", values: [], unit: null },
  { key: "state", label: "State", type: "STATE", values: [], unit: null },
  { key: "attr:mass", label: "Mass", type: "NUMBER", values: [], unit: "kg" },
];
const relations = [
  {
    key: "uses",
    label: "Uses (structure)",
    from: ["PART"],
    to: ["PART"],
    fields: [{ key: "quantity", label: "Quantity", type: "NUMBER", values: [], unit: null }],
  },
];

function api() {
  return fakeApi({
    "GET /api/v1/me": () => me(ALICE, true),
    "GET /api/v1/reports": () => [],
    "GET /api/v1/types": () => types.map((t) => ({ ...t, description: null, labels: {}, icon: null, instantiable: true })),
    "GET /api/v1/lifecycles": () => [
      {
        ...lifecycle(),
        id: "lc",
        name: "Basic",
        labels: {},
        initialState: "IN_WORK",
        system: true,
        version: 1,
        latest: true,
        transitions: [],
      },
    ],
    "GET /api/v1/query/relations": () => relations,
    "GET /api/v1/query/fields": () => fields,
    "POST /api/v1/query/run": () => ({
      columns: [
        { label: "Number", type: "TEXT", alias: "part", field: "number", aggregate: null, unit: null },
        { label: "Mass", type: "NUMBER", alias: "part", field: "attr:mass", aggregate: null, unit: "kg" },
      ],
      rows: [{ values: ["VX3-BP-60", 395], objects: { part: "p1" } }],
      capped: false,
    }),
    "POST /api/v1/reports": (req) => ({
      id: "r1",
      owner: ALICE,
      settings: req.body,
      nextRunAt: null,
      lastRunAt: null,
      lastTotal: null,
      updatedAt: "2026-11-22T10:00:00Z",
    }),
  });
}

describe("report builder", () => {
  it("builds a query with a link and a condition, previews it and saves it", async () => {
    const calls = api();
    renderAt("/reports");
    await userEvent.click(await screen.findByRole("button", { name: "New report" }));
    const dialog = await screen.findByRole("dialog");

    // The preview runs on its own.
    const preview = await within(dialog).findByRole("table", { name: "Preview" });
    expect(within(preview).getByRole("link", { name: "VX3-BP-60" })).toHaveAttribute("href", "/objects/p1");
    expect(within(preview).getByText("395 kg")).toBeInTheDocument();

    await userEvent.type(within(dialog).getByRole("textbox", { name: "Name" }), "Heavy assemblies");
    await userEvent.click(within(dialog).getByRole("button", { name: "Follow a link" }));
    expect(within(dialog).getByRole("combobox", { name: "Follow" })).toHaveValue("uses");

    await userEvent.click(within(dialog).getByRole("button", { name: "Condition" }));
    await userEvent.selectOptions(within(dialog).getByRole("combobox", { name: "Field" }), "part|attr:mass");
    await userEvent.selectOptions(within(dialog).getByRole("combobox", { name: "Operator" }), "GT");
    await userEvent.type(within(dialog).getByRole("spinbutton", { name: "Value" }), "100");
    await userEvent.selectOptions(within(dialog).getByRole("combobox", { name: "Total of column 1" }), "COUNT");

    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(calls.some((c) => c.method === "POST" && c.path === "/api/v1/reports")).toBe(true));
    const body = calls.find((c) => c.method === "POST" && c.path === "/api/v1/reports")!.body as {
      name: string;
      definition: {
        from: { typeId: string };
        links: { relation: string; from: string; alias: string }[];
        where: unknown;
        columns: { aggregate?: string }[];
      };
    };
    expect(body.name).toBe("Heavy assemblies");
    expect(body.definition.from.typeId).toBe(PART_TYPE);
    expect(body.definition.links).toEqual([expect.objectContaining({ relation: "uses", from: "part", alias: "child" })]);
    expect(body.definition.where).toEqual({
      group: "AND",
      conditions: [{ field: { alias: "part", field: "attr:mass" }, operator: "GT", value: "100", conditions: [] }],
    });
    expect(body.definition.columns[0]!.aggregate).toBe("COUNT");
  });
});
