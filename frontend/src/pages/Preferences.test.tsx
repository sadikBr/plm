import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { fakeApi } from "../test/fakeApi";
import { ALICE, PRODUCT, me, principal, product, types } from "../test/fixtures";
import { renderAt } from "../test/render";

const ORG = "01a1f962-0000-7000-8000-00000000000o";
const def = (key: string, label: string, type: string, extra: Record<string, unknown> = {}) => ({
  key,
  label,
  description: `${label} help`,
  category: "General",
  type,
  default: null,
  levels: ["SITE", "CONTEXT"],
  min: null,
  max: null,
  options: [],
  ...extra,
});

const prefs = [
  {
    definition: def("ui.tablePageSize", "Rows per page", "INTEGER", { min: 10, max: 500 }),
    effective: { key: "ui.tablePageSize", value: 100, source: "CONTEXT", sourceContextId: ORG, lockedAt: null },
    own: null,
    canSet: true,
  },
  {
    definition: def("versioning.checkInCommentRequired", "Check-in comment required", "BOOLEAN"),
    effective: { key: "versioning.checkInCommentRequired", value: true, source: "CONTEXT", sourceContextId: ORG, lockedAt: ORG },
    own: null,
    canSet: false,
  },
];

function api() {
  return fakeApi({
    "GET /api/v1/me": () => me(ALICE, true),
    "GET /api/v1/contexts/site": () => ({ ...product, id: "site", parentId: null, kind: "SITE", name: "Site" }),
    "GET /api/v1/contexts/:id": (_, { id }) => (id === ORG ? { ...product, id: ORG, kind: "ORGANIZATION", name: "Acme", parentId: null } : product),
    "GET /api/v1/contexts/:id/children": () => [],
    "GET /api/v1/contexts/:id/permissions": () => ["READ", "MANAGE"],
    "GET /api/v1/contexts/:id/objects": () => [],
    "GET /api/v1/objects": () => [],
    "GET /api/v1/preferences/effective": () => [],
    "GET /api/v1/contexts/:id/preferences": () => prefs,
    "PUT /api/v1/contexts/:id/preferences/:key": () => prefs[0],
    "GET /api/v1/types": () => types,
    "GET /api/v1/principals/:id": (_, { id }) => principal(id!),
  });
}

describe("Preferences tab", () => {
  it("shows where each value comes from, and what's locked above", async () => {
    api();
    renderAt(`/contexts/${PRODUCT}?tab=preferences`);

    const rows = within(await screen.findByRole("table", { name: "General preferences" }));
    const pageSize = rows.getByRole("row", { name: "Rows per page" });
    expect(within(pageSize).getByText("100")).toBeInTheDocument();
    expect(await within(pageSize).findByRole("link", { name: "Acme" })).toBeInTheDocument();
    const comment = rows.getByRole("row", { name: "Check-in comment required" });
    expect(within(comment).getByText(/Locked by/)).toBeInTheDocument();
    expect(within(comment).queryByRole("button", { name: "Save" })).not.toBeInTheDocument();
  });

  it("sets a value here, optionally locked", async () => {
    const calls = api();
    renderAt(`/contexts/${PRODUCT}?tab=preferences`);

    const row = within(await screen.findByRole("row", { name: "Rows per page" }));
    await userEvent.clear(row.getByLabelText("Rows per page"));
    await userEvent.type(row.getByLabelText("Rows per page"), "25");
    await userEvent.click(row.getByLabelText("Lock"));
    await userEvent.click(row.getByRole("button", { name: "Save" }));

    await waitFor(() =>
      expect(calls.find((c) => c.method === "PUT")).toMatchObject({
        path: `/api/v1/contexts/${PRODUCT}/preferences/ui.tablePageSize`,
        body: { value: 25, locked: true },
      }),
    );
  });
});
