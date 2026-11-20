import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { VersionedObject } from "../api/queries";
import { Reply, fakeApi, problem } from "../test/fakeApi";
import { ALICE, BOB, attributeRoutes, iteration, lifecycle, me, object, objectActions, principal, product, types } from "../test/fixtures";
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
    "GET /api/v1/objects/:id/lifecycle": () => lifecycle(obj.latest.state),
    // The registry's answer follows the (possibly overridden) user and lifecycle of each test.
    "GET /api/v1/objects/:id/actions": async (req, params) => {
      const who = (await (extra["GET /api/v1/me"] ?? (() => me(BOB)))(req, params)) as ReturnType<typeof me>;
      const lc = (await (extra["GET /api/v1/objects/:id/lifecycle"] ?? (() => lifecycle(obj.latest.state)))(req, params)) as ReturnType<
        typeof lifecycle
      >;
      return objectActions(obj, permissions, lc, { userId: who.user.id, siteAdmin: who.siteAdmin });
    },
    ...attributeRoutes,
    ...extra,
  });
}

describe("ObjectPage", () => {
  it("lets a member check out an In Work object, but not revise it yet", async () => {
    const obj = object();
    const calls = apiFor(obj, ["CREATE", "MODIFY", "READ", "REVISE"], {
      "POST /api/v1/objects/:id/checkout": () => iteration({ version: "A.2", status: "WORKING", checkedOutBy: BOB }),
    });
    renderAt(`/objects/${obj.id}`);

    expect(await screen.findByTestId("version")).toHaveTextContent("A.1");
    const revise = await screen.findByRole("button", { name: "Revise" });
    await waitFor(() => expect(revise).toHaveAttribute("title", "Only Released objects can be revised (this one is In Work)"));
    expect(revise).toBeDisabled();
    await waitFor(() => expect(screen.getByRole("button", { name: "Check out" })).toBeEnabled());
    await userEvent.click(screen.getByRole("button", { name: "Check out" }));

    await waitFor(() => expect(calls.some((c) => c.method === "POST" && c.path.endsWith("/checkout"))).toBe(true));
  });

  it("makes Released objects read-only and revisable", async () => {
    const obj = object({ latest: iteration({ state: "RELEASED" }) });
    const calls = apiFor(obj, ["CREATE", "MODIFY", "READ", "REVISE"], {
      "POST /api/v1/objects/:id/revise": () => object({ latest: iteration({ version: "B.1", revision: "B" }) }),
    });
    renderAt(`/objects/${obj.id}`);

    const checkOut = await screen.findByRole("button", { name: "Check out" });
    await waitFor(() => expect(checkOut).toHaveAttribute("title", "Released objects can't be edited. Revise to start a new revision."));
    expect(checkOut).toBeDisabled();
    await userEvent.click(screen.getByRole("button", { name: "Revise" }));

    await waitFor(() => expect(calls.some((c) => c.method === "POST" && c.path.endsWith("/revise"))).toBe(true));
  });

  it("lets a manager move the object along its lifecycle, with a comment", async () => {
    const obj = object();
    const calls = apiFor(obj, ["MODIFY", "READ", "PROMOTE"], {
      "GET /api/v1/objects/:id/lifecycle": () => lifecycle("IN_WORK", { canPromote: true }),
      "POST /api/v1/objects/:id/state": () => object({ latest: iteration({ state: "UNDER_REVIEW" }) }),
    });
    renderAt(`/objects/${obj.id}`);

    await waitFor(() => expect(screen.getByRole("button", { name: "Set state" })).toBeEnabled());
    await userEvent.click(screen.getByRole("button", { name: "Set state" }));
    expect(screen.getAllByRole("menuitem").map((m) => m.textContent)).toEqual(["Under Review", "Canceled"]);
    await userEvent.click(screen.getByRole("menuitem", { name: "Under Review" }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.type(within(dialog).getByLabelText("Comment"), "Ready for DR-12");
    await userEvent.click(within(dialog).getByRole("button", { name: "Set state" }));

    await waitFor(() =>
      expect(calls.find((c) => c.path.endsWith("/state"))?.body).toEqual({
        state: "UNDER_REVIEW",
        comment: "Ready for DR-12",
        override: false,
      }),
    );
  });

  it("offers site administrators an override to any state", async () => {
    const obj = object();
    apiFor(obj, ["READ"], { "GET /api/v1/objects/:id/lifecycle": () => lifecycle("IN_WORK", { canOverride: true }) });
    renderAt(`/objects/${obj.id}`);

    await waitFor(() => expect(screen.getByRole("button", { name: "Set state" })).toBeEnabled());
    await userEvent.click(screen.getByRole("button", { name: "Set state" }));
    expect(screen.getByRole("menuitem", { name: /Released \(override\)/ })).toBeInTheDocument();
    await userEvent.click(screen.getByRole("menuitem", { name: /Released \(override\)/ }));
    expect(await screen.findByText(/ignores the lifecycle's transitions/)).toBeInTheDocument();
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
      "PATCH /api/v1/objects/:id/working-copy": (req) =>
        req.json().then((b: { attributes: Record<string, unknown> }) => iteration({ attributes: b.attributes })),
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

  it("lets a manager rename and renumber from the More menu", async () => {
    const obj = object();
    const calls = apiFor(obj, ["READ", "MODIFY", "MANAGE"], {
      "POST /api/v1/objects/:id/rename": () => ({ ...obj, number: "BRK-1", name: "Motor bracket" }),
    });
    renderAt(`/objects/${obj.id}`);

    await userEvent.click(await screen.findByRole("button", { name: "More" }));
    await userEvent.click(screen.getByRole("menuitem", { name: /Rename or renumber/ }));
    const dialog = screen.getByRole("dialog");
    await userEvent.clear(within(dialog).getByLabelText("Number"));
    await userEvent.type(within(dialog).getByLabelText("Number"), "BRK-1");
    await userEvent.type(within(dialog).getByLabelText("Reason"), "ERP alignment");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() =>
      expect(calls.find((c) => c.path.endsWith("/rename"))?.body).toEqual({ number: "BRK-1", name: null, comment: "ERP alignment" }),
    );
  });

  it("explains why members can't rename", async () => {
    apiFor(object(), ["READ", "MODIFY"]);
    renderAt(`/objects/${object().id}`);
    await userEvent.click(await screen.findByRole("button", { name: "More" }));
    expect(screen.getByRole("menuitem", { name: /Rename or renumber/ })).toBeDisabled();
  });

  it("lets site administrators move an object to another lifecycle, choosing the state when needed", async () => {
    const obj = object();
    const quick = {
      id: "quick",
      name: "Quick",
      labels: { en: "Quick" },
      initialState: "DRAFT",
      system: false,
      version: 1,
      latest: true,
      states: [
        { key: "DRAFT", labels: { en: "Draft" }, editable: true, revisable: false },
        { key: "DONE", labels: { en: "Done" }, editable: false, revisable: true },
      ],
      transitions: [],
    };
    const calls = apiFor(obj, ["READ", "MODIFY"], {
      "GET /api/v1/me": () => me(ALICE, true),
      "GET /api/v1/objects/:id/lifecycle": () => ({ ...lifecycle(obj.latest.state), lifecycleVersion: 1, lifecycleLatest: false }),
      "GET /api/v1/lifecycles": () => [quick],
      "POST /api/v1/objects/:id/lifecycle": () => obj,
    });
    renderAt(`/objects/${obj.id}`);

    await userEvent.click(await screen.findByRole("button", { name: "More" }));
    await userEvent.click(screen.getByRole("menuitem", { name: /Change lifecycle/ }));
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText(/a newer version exists/)).toBeInTheDocument();
    expect(await within(dialog).findByText(/has no In Work state/)).toBeInTheDocument();
    await userEvent.selectOptions(within(dialog).getByLabelText("State"), "DRAFT");
    await userEvent.click(within(dialog).getByRole("button", { name: "Move" }));

    await waitFor(() =>
      expect(calls.find((c) => c.path.endsWith("/lifecycle") && c.method === "POST")?.body).toEqual({
        lifecycleId: "quick",
        state: "DRAFT",
      }),
    );
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
