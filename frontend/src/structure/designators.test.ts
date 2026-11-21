import { describe, expect, it } from "vitest";
import { formatDesignators } from "./designators";

describe("reference designators", () => {
  it("shows runs as ranges", () => {
    expect(formatDesignators(["R1", "R2", "R3", "R7"])).toBe("R1-R3, R7");
    expect(formatDesignators(["C1", "C2"])).toBe("C1, C2");
    expect(formatDesignators(["U1", "R1", "R2", "R3", "R4"])).toBe("U1, R1-R4");
    expect(formatDesignators([])).toBe("");
  });
});
