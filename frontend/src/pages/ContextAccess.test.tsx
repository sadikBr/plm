import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { fakeApi } from "../test/fakeApi";
import { ALICE, BOB, PART_TYPE, PRODUCT, lifecycle, me, principal, product, types } from "../test/fixtures";
import { renderAt } from "../test/render";

const SITE = "00000000-0000-7000-8000-000000000011";

const roles = [
  { key: "VIEWER", labels: { en: "Viewer" }, description: "Can see.", system: true, active: true, sortOrder: 10 },
  { key: "MEMBER", labels: { en: "Member" }, description: "Works on objects.", system: true, active: true, sortOrder: 20 },
  { key: "MANAGER", labels: { en: "Manager" }, description: "Manages.", system: true, active: true, sortOrder: 30 },
  { key: "DESIGN_ENGINEER", labels: { en: "Design engineer" }, description: null, system: false, active: true, sortOrder: 40 },
];

const policy = (over: Record<string, unknown>) => ({
  id: "p-site-member",
  contextId: SITE,
  role: "MEMBER",
  typeId: null,
  state: null,
  effect: "ALLOW",
  permissions: ["READ", "CREATE", "MODIFY", "REVISE"],
  inherited: true,
  createdAt: "2026-11-20T09:00:00Z",
  createdBy: "system",
  ...over,
});

const denyFasteners = policy({
  id: "p-deny",
  contextId: PRODUCT,
  typeId: PART_TYPE,
  effect: "DENY",
  permissions: ["MODIFY"],
  inherited: false,
  createdBy: ALICE,
});

function api() {
  const lc = lifecycle();
  return fakeApi({
    "GET /api/v1/me": () => me(ALICE),
    "GET /api/v1/contexts/site": () => ({ ...product, id: SITE, parentId: null, kind: "SITE", name: "Site" }),
    "GET /api/v1/contexts/:id": (_, { id }) =>
      id === SITE ? { ...product, id: SITE, parentId: null, kind: "SITE", name: "Site" } : { ...product, parentId: SITE },
    "GET /api/v1/contexts/:id/children": () => [],
    "GET /api/v1/contexts/:id/objects": () => ({ items: [], nextCursor: null }),
    "GET /api/v1/contexts/:id/permissions": () => ["READ", "CREATE", "MODIFY", "REVISE", "MANAGE", "PROMOTE"],
    "GET /api/v1/contexts/:id/members": () => [
      { contextId: PRODUCT, principalId: BOB, role: "MEMBER", addedAt: "2026-11-20T09:00:00Z", addedBy: ALICE },
    ],
    "GET /api/v1/contexts/:id/members/effective": () => [
      { contextId: PRODUCT, principalId: BOB, role: "MEMBER", addedAt: "2026-11-20T09:00:00Z", addedBy: ALICE },
      { contextId: SITE, principalId: ALICE, role: "MANAGER", addedAt: "2026-11-20T09:00:00Z", addedBy: "system" },
    ],
    "GET /api/v1/contexts/:id/policies": () => [policy({}), denyFasteners],
    "POST /api/v1/contexts/:id/policies": () => policy({ id: "p-new", contextId: PRODUCT, inherited: false }),
    "DELETE /api/v1/policies/:id": () => undefined,
    "GET /api/v1/contexts/:id/access/explain": () => ({
      principalId: BOB,
      contextId: PRODUCT,
      typeId: PART_TYPE,
      state: null,
      roles: [{ contextId: PRODUCT, principalId: BOB, role: "MEMBER", addedAt: "2026-11-20T09:00:00Z", addedBy: ALICE }],
      siteAdmin: false,
      permissions: [
        { permission: "READ", granted: true, allowedBy: [policy({})], deniedBy: [] },
        { permission: "MODIFY", granted: false, allowedBy: [policy({})], deniedBy: [denyFasteners] },
        { permission: "MANAGE", granted: false, allowedBy: [], deniedBy: [] },
      ],
    }),
    "GET /api/v1/team-roles": () => roles,
    "GET /api/v1/types": () => types,
    "GET /api/v1/lifecycles": () => [
      {
        id: lc.lifecycleId,
        name: "Basic",
        labels: { en: "Basic" },
        initialState: "IN_WORK",
        system: true,
        states: lc.states,
        transitions: [],
      },
    ],
    "GET /api/v1/principals/:id": (_, { id }) => principal(id!),
  });
}

describe("Access tab", () => {
  it("lists the rules in effect, inherited ones first, and only lets you delete this context's own", async () => {
    const calls = api();
    renderAt(`/contexts/${PRODUCT}?tab=access`);

    const rules = await screen.findByRole("table", { name: "Access rules" });
    const rows = within(rules).getAllByRole("row").slice(1);
    expect(rows).toHaveLength(2);
    expect(within(rows[0]!).getByText("Allow")).toBeInTheDocument();
    expect(await within(rows[0]!).findByRole("link", { name: "Site" })).toBeInTheDocument();
    expect(within(rows[0]!).queryByRole("button")).not.toBeInTheDocument();
    expect(within(rows[1]!).getByText("Deny")).toBeInTheDocument();
    expect(within(rows[1]!).getByText("and subtypes")).toBeInTheDocument();
    expect(within(rows[1]!).getByText("This context")).toBeInTheDocument();

    await userEvent.click(within(rows[1]!).getByRole("button", { name: /Delete rule: deny Member/ }));
    await waitFor(() => expect(calls.some((c) => c.method === "DELETE" && c.path === "/api/v1/policies/p-deny")).toBe(true));
  });

  it("adds a rule for a custom role", async () => {
    const calls = api();
    renderAt(`/contexts/${PRODUCT}?tab=access`);

    const form = await screen.findByRole("form", { name: "Add a rule" });
    await userEvent.click(within(form).getByRole("radio", { name: "Deny" }));
    await within(form).findByRole("option", { name: "Design engineer" });
    await userEvent.selectOptions(within(form).getByLabelText("Role"), "DESIGN_ENGINEER");
    await userEvent.selectOptions(within(form).getByLabelText("Type"), PART_TYPE);
    await userEvent.selectOptions(within(form).getByLabelText("Lifecycle state"), "RELEASED");
    await userEvent.click(within(form).getByRole("checkbox", { name: /Revise/ }));
    await userEvent.click(within(form).getByRole("button", { name: "Add rule" }));

    await waitFor(() =>
      expect(calls.find((c) => c.method === "POST")?.body).toEqual({
        role: "DESIGN_ENGINEER",
        typeId: PART_TYPE,
        state: "RELEASED",
        effect: "DENY",
        permissions: ["READ", "REVISE"],
      }),
    );
  });

  it("explains a person's access with the rules that decide it", async () => {
    api();
    renderAt(`/contexts/${PRODUCT}?tab=access`);

    const check = await screen.findByRole("table", { name: "Access check" });
    const modify = within(check).getByRole("row", { name: /Modify/ });
    expect(within(modify).getByText("Denied")).toBeInTheDocument();
    expect(within(modify).getByText(/Denied by: Member · Part and subtypes · Any state/)).toBeInTheDocument();
    expect(within(within(check).getByRole("row", { name: /Read/ })).getByText("Allowed")).toBeInTheDocument();
    expect(within(within(check).getByRole("row", { name: /Manage/ })).getByText("No rule grants it")).toBeInTheDocument();
  });
});

describe("Team tab", () => {
  it("offers every active role, and shows members inherited from parent contexts", async () => {
    api();
    renderAt(`/contexts/${PRODUCT}?tab=team`);

    const add = await screen.findByRole("form", { name: "Add a member" });
    expect(await within(add).findByRole("option", { name: "Design engineer" })).toBeInTheDocument();
    const inherited = await screen.findByRole("table", { name: "Inherited members" });
    expect(await within(inherited).findByText("Alice Admin")).toBeInTheDocument();
    expect(within(inherited).getByText("Manager")).toBeInTheDocument();
  });
});
