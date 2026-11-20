import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it } from "vitest";
import { fakeApi } from "../test/fakeApi";
import { ALICE, PRODUCT, me, principal, product, types } from "../test/fixtures";
import { renderAt } from "../test/render";

const SITE = { ...product, id: "site-id", parentId: null, kind: "SITE", name: "Site" };
const ORG = { ...product, id: "org-id", parentId: "site-id", kind: "ORGANIZATION", name: "Acme" };
const DRONE = { ...product, parentId: "org-id" };

function api(showOrganization = true) {
  return fakeApi({
    "GET /api/v1/me": () => me(ALICE, true),
    "GET /api/v1/contexts/site": () => SITE,
    "GET /api/v1/contexts": (req) => {
      const params = new URL(req.url).searchParams;
      if (params.get("kind")) return [ORG, DRONE].filter((c) => c.kind === params.get("kind"));
      return params.get("q") === "dro" ? [DRONE] : [];
    },
    "GET /api/v1/contexts/:id": (_, { id }) => [SITE, ORG, DRONE].find((c) => c.id === id),
    "GET /api/v1/contexts/:id/children": (_, { id }) => (id === "site-id" ? [ORG] : id === "org-id" ? [DRONE] : []),
    "GET /api/v1/contexts/:id/path": () => [SITE, ORG, DRONE],
    "GET /api/v1/contexts/:id/permissions": () => ["READ"],
    "GET /api/v1/objects": () => [],
    "GET /api/v1/preferences/effective": () => [
      { key: "ui.showOrganization", value: showOrganization, source: "USER", sourceContextId: null, lockedAt: null },
    ],
    "GET /api/v1/types": () => types,
    "GET /api/v1/principals/:id": (_, { id }) => principal(id!),
  });
}

describe("Navigator", () => {
  beforeEach(() => localStorage.clear());

  it("groups contexts by kind and lists the current kind's contexts, highlighting where we are", async () => {
    api();
    renderAt(`/contexts/${PRODUCT}`);

    const browse = await screen.findByRole("list", { name: "Browse by kind" });
    for (const kind of ["Organizations", "Products", "Libraries", "Projects"]) {
      expect(within(browse).getByRole("link", { name: new RegExp(kind) })).toBeInTheDocument();
    }
    const products = await within(browse).findByRole("list", { name: "Products" });
    expect(await within(products).findByRole("link", { name: /Drone X1/ })).toHaveAttribute("aria-current", "page");
    // Other kinds stay closed until asked.
    expect(within(browse).queryByRole("list", { name: "Libraries" })).not.toBeInTheDocument();
    await userEvent.click(within(browse).getByRole("button", { name: "Show libraries" }));
    expect(await within(browse).findByText("None you can see")).toBeInTheDocument();
  });

  it("shows each product's organization unless the user turned that off", async () => {
    api();
    renderAt(`/contexts/${PRODUCT}`);
    const products = await screen.findByRole("list", { name: "Products" });
    expect(await within(products).findByText("Acme")).toHaveClass("nav-org");
  });

  it("hides organization names when the preference is off", async () => {
    api(false);
    renderAt("/browse/products");
    const table = await screen.findByRole("table", { name: "Products" });
    expect(within(table).queryByRole("columnheader", { name: "Organization" })).not.toBeInTheDocument();
    const products = await screen.findByRole("list", { name: "Products" });
    await within(products).findByRole("link", { name: /Drone X1/ });
    expect(within(products).queryByText("Acme")).not.toBeInTheDocument();
  });

  it("lists every context of a kind on its browse page", async () => {
    api();
    renderAt("/browse/products");
    const table = await screen.findByRole("table", { name: "Products" });
    expect(within(table).getByRole("link", { name: "Drone X1" })).toBeInTheDocument();
    expect(await within(table).findByRole("link", { name: "Acme" })).toBeInTheDocument();
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
