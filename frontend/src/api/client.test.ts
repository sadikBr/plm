import { describe, expect, it } from "vitest";
import { fakeApi, problem } from "../test/fakeApi";
import { ApiError, api, unwrap } from "./client";

describe("API client", () => {
  it("sends the bearer token and returns the body", async () => {
    const calls = fakeApi({ "GET /api/v1/types": () => [{ id: "t1" }] });

    const types = await unwrap(api.GET("/api/v1/types"));

    expect(types).toEqual([{ id: "t1" }]);
    expect(calls[0]?.auth).toBe("Bearer test-token");
  });

  it("turns problem details into an ApiError with the server's code", async () => {
    fakeApi({ "POST /api/v1/objects/:id/checkout": () => problem(409, "ALREADY_CHECKED_OUT", "Checked out by Bob") });

    const error = await unwrap(api.POST("/api/v1/objects/{id}/checkout", { params: { path: { id: "x" } } })).catch(
      (e: unknown) => e,
    );

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 409, code: "ALREADY_CHECKED_OUT", message: "Checked out by Bob" });
  });
});
