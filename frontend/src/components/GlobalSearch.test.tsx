import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { fakeApi, problem } from "../test/fakeApi";
import { BOB, attributeRoutes, me, object, principal, product, types } from "../test/fixtures";
import { renderAt } from "../test/render";

function api() {
  const bracket = object({ id: "o1", number: "PN-100", name: "Bracket left" });
  const cover = object({ id: "o2", number: "PN-200", name: "Bracket cover" });
  return fakeApi({
    "GET /api/v1/me": () => me(BOB),
    "GET /api/v1/contexts/site": () => problem(404, "CONTEXT_NOT_FOUND"),
    "GET /api/v1/contexts/:id": () => ({ ...product, parentId: null }),
    "GET /api/v1/contexts/:id/permissions": () => ["READ"],
    "GET /api/v1/types": () => types,
    "GET /api/v1/principals/:id": (_, { id }) => principal(id!),
    "GET /api/v1/objects": (req) => (new URL(req.url).searchParams.get("q") === "brack" ? [bracket, cover] : []),
    "GET /api/v1/objects/:id": (_, { id }) => (id === "o2" ? cover : bracket),
    ...attributeRoutes,
  });
}

describe("GlobalSearch", () => {
  it("suggests matches and opens the highlighted one with Enter", async () => {
    const calls = api();
    renderAt("/");

    const box = screen.getByRole("combobox", { name: "Search" });
    await userEvent.type(box, "brack");
    expect(await screen.findByRole("option", { name: /PN-100/ })).toHaveAttribute("aria-selected", "true");

    await userEvent.keyboard("{ArrowDown}{Enter}");

    expect(await screen.findByTestId("version")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("PN-200");
    expect(calls.some((c) => c.path.startsWith("/api/v1/objects?") && c.path.includes("q=brack"))).toBe(true);
  });

  it("goes to the full results page when no suggestion is picked", async () => {
    api();
    renderAt("/");

    await userEvent.type(screen.getByRole("combobox", { name: "Search" }), "brack");
    await screen.findByRole("option", { name: /PN-100/ });
    await userEvent.keyboard("{ArrowDown}{ArrowDown}{Enter}");

    await waitFor(() => expect(screen.getByRole("table", { name: "Search results" })).toBeInTheDocument());
    expect(screen.getAllByRole("row")).toHaveLength(3); // header + 2 results
  });

  it("focuses with the / shortcut", async () => {
    api();
    renderAt("/");

    await userEvent.keyboard("/");

    expect(screen.getByRole("combobox", { name: "Search" })).toHaveFocus();
  });
});
