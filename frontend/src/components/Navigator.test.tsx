import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it } from "vitest";
import { fakeApi } from "../test/fakeApi";
import { ALICE, PRODUCT, me, principal, product, types } from "../test/fixtures";
import { renderAt } from "../test/render";

const SITE = { ...product, id: "site-id", parentId: null, kind: "SITE", name: "Site" };
const ORG = { ...product, id: "org-id", parentId: "site-id", kind: "ORGANIZATION", name: "Acme" };
const DRONE = { ...product, parentId: "org-id" };

function api() {
  return fakeApi({
    "GET /api/v1/me": () => me(ALICE, true),
    "GET /api/v1/contexts/site": () => SITE,
    "GET /api/v1/contexts": (req) => (new URL(req.url).searchParams.get("q") === "dro" ? [DRONE] : []),
    "GET /api/v1/contexts/:id": (_, { id }) => [SITE, ORG, DRONE].find((c) => c.id === id),
    "GET /api/v1/contexts/:id/children": (_, { id }) => (id === "site-id" ? [ORG] : id === "org-id" ? [DRONE] : []),
    "GET /api/v1/contexts/:id/path": () => [SITE, ORG, DRONE],
    "GET /api/v1/contexts/:id/permissions": () => ["READ"],
    "GET /api/v1/objects": () => [],
    "GET /api/v1/preferences/effective": () => [],
    "GET /api/v1/types": () => types,
    "GET /api/v1/principals/:id": (_, { id }) => principal(id!),
  });
}

describe("Navigator", () => {
  beforeEach(() => localStorage.clear());

  it("opens the tree down to the current context and highlights it", async () => {
    api();
    renderAt(`/contexts/${PRODUCT}`);

    const tree = await screen.findByRole("tree", { name: "All contexts" });
    const drone = await within(tree).findByRole("treeitem", { name: /Drone X1/, selected: true });
    expect(drone).toBeInTheDocument();
    expect(within(tree).getByRole("button", { name: "Collapse Acme" })).toHaveAttribute("aria-expanded", "true");
  });

  it("finds contexts by name, and pins them to the top", async () => {
    api();
    renderAt(`/contexts/${PRODUCT}`);

    await userEvent.type(await screen.findByLabelText("Filter contexts"), "dro");
    const matches = await screen.findByRole("region", { name: "1 match" });
    await userEvent.click(within(matches).getByRole("button", { name: "Pin Drone X1" }));
    await userEvent.click(screen.getByLabelText("Clear filter"));

    const pinned = await screen.findByRole("region", { name: "Pinned" });
    expect(within(pinned).getByRole("link", { name: /Drone X1/ })).toBeInTheDocument();
  });

  it("collapses to a rail and remembers it", async () => {
    api();
    renderAt(`/contexts/${PRODUCT}`);
    await userEvent.click(await screen.findByRole("button", { name: "Collapse navigator" }));
    expect(screen.getByRole("button", { name: "Expand navigator" })).toBeInTheDocument();
    expect(localStorage.getItem("plm.nav.collapsed")).toBe("true");
  });
});
