import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { VersionedObject } from "../api/queries";
import { Reply, fakeApi, problem } from "../test/fakeApi";
import { ALICE, BOB, iteration, me, object, principal, product, types } from "../test/fixtures";
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
    expect(screen.queryByRole("button", { name: "Check out" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Revise" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Check in" })).not.toBeInTheDocument();
  });

  it("lets the owner edit the working copy and check in with a comment", async () => {
    const obj = object({ checkedOutBy: BOB });
    const calls = apiFor(obj, ["MODIFY", "READ"], {
      "GET /api/v1/objects/:id/working-copy": () =>
        iteration({ id: "wc", version: "A.2", status: "WORKING", checkedOutBy: BOB }),
      "POST /api/v1/objects/:id/checkin": () => object({ latest: iteration({ version: "A.2" }) }),
    });
    renderAt(`/objects/${obj.id}`);

    expect(await screen.findByText("Only you see these edits until you check in.")).toBeInTheDocument();
    await userEvent.click(await screen.findByRole("button", { name: "Check in" }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.type(within(dialog).getByLabelText("Comment"), "Titanium");
    await userEvent.click(within(dialog).getByRole("button", { name: "Check in" }));

    await waitFor(() => {
      const checkin = calls.find((c) => c.path.endsWith("/checkin"));
      expect(checkin?.body).toEqual({ comment: "Titanium" });
    });
  });

  it("is read-only for viewers", async () => {
    const obj = object();
    apiFor(obj, ["READ"]);
    renderAt(`/objects/${obj.id}`);

    expect(await screen.findByText("AL6061")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Check out" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Revise" })).not.toBeInTheDocument();
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
