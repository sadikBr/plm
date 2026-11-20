import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { VersionedObject } from "../api/queries";
import { Reply, fakeApi, problem } from "../test/fakeApi";
import { ALICE, BOB, attributeRoutes, iteration, me, object, principal, product, types } from "../test/fixtures";
import { renderAt } from "../test/render";

/** The API as seen by bob, with [obj] and the given permissions on its context. */
function apiFor(obj: VersionedObject, permissions: string[], extra: Parameters<typeof fakeApi>[0] = {}) {
  return fakeApi({
    "GET /api/v1/me": () => me(BOB),
    "GET /api/v1/contexts/site": () => problem(404, "CONTEXT_NOT_FOUND"),
    "GET /api/v1/contexts/:id": () => ({ ...product, parentId: null }),
    "GET /api/v1/contexts/:id/permissions": () => permissions,
    "GET /api/v1/types": () => types,
    "GET /api/v1/principals/:id": (_, { id }) => principal(id!),
    "GET /api/v1/objects/:id": () => obj,
    ...attributeRoutes,
    ...extra,
  });
}

describe("ObjectPage", () => {
  it("lets a member check out and revise a free object", async () => {
    const obj = object();
    const calls = apiFor(obj, ["CREATE", "MODIFY", "READ", "REVISE"], {
      "POST /api/v1/objects/:id/checkout": () => iteration({ version: "A.2", status: "WORKING", checkedOutBy: BOB }),
    });
    renderAt(`/objects/${obj.id}`);

    expect(await screen.findByTestId("version")).toHaveTextContent("A.1");
    expect(await screen.findByRole("button", { name: "Revise" })).toBeEnabled();
    await userEvent.click(await screen.findByRole("button", { name: "Check out" }));

    await waitFor(() => expect(calls.some((c) => c.method === "POST" && c.path.endsWith("/checkout"))).toBe(true));
  });

  it("shows who has it checked out and offers no actions to others", async () => {
    const obj = object({ checkedOutBy: ALICE });
    apiFor(obj, ["CREATE", "MODIFY", "READ", "REVISE"]);
    renderAt(`/objects/${obj.id}`);

    await waitFor(() => expect(screen.getByTestId("checked-out")).toHaveTextContent("Checked out by Alice Admin"));
    expect(screen.getByRole("button", { name: "Check out" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Revise" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Check in" })).toBeDisabled();
  });

  it("lets the owner edit the working copy with typed fields and check in", async () => {
    const obj = object({ checkedOutBy: BOB });
    const calls = apiFor(obj, ["MODIFY", "READ"], {
      "GET /api/v1/objects/:id/working-copy": () =>
        iteration({ id: "wc", version: "A.2", status: "WORKING", checkedOutBy: BOB, attributes: { source: "make", material: "AL6061" } }),
      "PATCH /api/v1/objects/:id/working-copy": (req) => req.json().then((b: { attributes: Record<string, unknown> }) => iteration({ attributes: b.attributes })),
      "POST /api/v1/objects/:id/checkin": () => object({ latest: iteration({ version: "A.2" }) }),
    });
    renderAt(`/objects/${obj.id}`);

    const material = await screen.findByLabelText("Material");
    await userEvent.clear(material);
    await userEvent.type(material, "Ti-6Al-4V");
    await userEvent.selectOptions(screen.getByLabelText("Source"), "buy");
    await userEvent.type(screen.getByLabelText("Mass"), "0.25");
    await userEvent.selectOptions(screen.getByLabelText("Mass unit"), "g");
    await userEvent.click(screen.getByRole("checkbox", { name: "End item" }));
    await userEvent.click(screen.getByRole("button", { name: "Save attributes" }));

    await waitFor(() =>
      expect(calls.find((c) => c.method === "PATCH")?.body).toEqual({
        attributes: { source: "buy", material: "Ti-6Al-4V", mass: { value: 0.25, unit: "g" }, endItem: true },
      }),
    );
    // Inactive attributes are not offered for editing.
    expect(screen.queryByLabelText("Legacy code")).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Check in" }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.type(within(dialog).getByLabelText("Comment"), "Titanium");
    await userEvent.click(within(dialog).getByRole("button", { name: "Check in" }));
    await waitFor(() => expect(calls.find((c) => c.path.endsWith("/checkin"))?.body).toEqual({ comment: "Titanium" }));
  });

  it("shows server-side attribute errors next to the field", async () => {
    const obj = object({ checkedOutBy: BOB });
    apiFor(obj, ["MODIFY", "READ"], {
      "GET /api/v1/objects/:id/working-copy": () => iteration({ id: "wc", status: "WORKING", checkedOutBy: BOB, attributes: {} }),
      "PATCH /api/v1/objects/:id/working-copy": () =>
        new Reply(422, {
          status: 422,
          code: "INVALID_ATTRIBUTES",
          detail: "Material must be at most 200 characters",
          details: { errors: [{ attribute: "material", code: "TOO_LONG", message: "Material must be at most 200 characters" }] },
        }),
    });
    renderAt(`/objects/${obj.id}`);

    await userEvent.type(await screen.findByLabelText("Material"), "x");
    await userEvent.click(screen.getByRole("button", { name: "Save attributes" }));

    expect(await screen.findByText("Material must be at most 200 characters")).toBeInTheDocument();
    expect(screen.getByLabelText("Material")).toHaveAttribute("aria-invalid", "true");
  });

  it("is read-only for viewers", async () => {
    const obj = object();
    apiFor(obj, ["READ"]);
    renderAt(`/objects/${obj.id}`);

    expect(await screen.findByText("AL6061")).toBeInTheDocument();
    expect(await screen.findByText("Make")).toBeInTheDocument(); // enumeration label, not the key
    expect(screen.getByRole("button", { name: "Check out" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Revise" })).toBeDisabled();
  });

  it("explains a 404 without revealing whether the object exists", async () => {
    fakeApi({
      "GET /api/v1/me": () => me(BOB),
      "GET /api/v1/contexts/site": () => problem(404, "CONTEXT_NOT_FOUND"),
      "GET /api/v1/objects/:id": () => problem(404, "OBJECT_NOT_FOUND"),
    });
    renderAt("/objects/nope");

    expect(await screen.findByRole("heading", { name: "Not found" })).toBeInTheDocument();
    expect(screen.getByText(/or you're not on the team/)).toBeInTheDocument();
  });

  it("shows server errors from actions", async () => {
    const obj = object();
    apiFor(obj, ["MODIFY", "READ"], {
      "POST /api/v1/objects/:id/checkout": () => new Reply(409, { status: 409, code: "ALREADY_CHECKED_OUT", detail: "Too slow" }),
    });
    renderAt(`/objects/${obj.id}`);

    await userEvent.click(await screen.findByRole("button", { name: "Check out" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Too slow");
    expect(screen.getByRole("alert")).toHaveTextContent("ALREADY_CHECKED_OUT");
  });
});
