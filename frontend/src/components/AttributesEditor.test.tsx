import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { AttributesEditor, fromRows, parseValue } from "./AttributesEditor";

describe("parseValue", () => {
  it.each([
    ["42", 42],
    ["-1.5", -1.5],
    ["true", true],
    ["null", null],
    ['{"value":1,"unit":"kg"}', { value: 1, unit: "kg" }],
    ["[1,2]", [1, 2]],
    ["AL6061", "AL6061"],
    ["{not json", "{not json"],
    ["007", 7],
  ])("%s", (text, expected) => {
    expect(parseValue(text)).toEqual(expected);
  });
});

describe("fromRows", () => {
  it("skips empty rows and rejects blank or duplicate names", () => {
    expect(fromRows([{ key: "a", value: "1" }, { key: "", value: "" }])).toEqual({ attributes: { a: 1 } });
    expect(fromRows([{ key: "", value: "x" }])).toEqual({ error: "Every attribute needs a name." });
    expect(fromRows([{ key: "a", value: "1" }, { key: " a ", value: "2" }])).toEqual({ error: '"a" appears twice.' });
  });
});

describe("AttributesEditor", () => {
  it("edits, adds and saves typed values", async () => {
    const onSave = vi.fn();
    render(<AttributesEditor initial={{ material: "AL6061" }} onSave={onSave} />);

    await userEvent.clear(screen.getByLabelText("Attribute 1 value"));
    await userEvent.type(screen.getByLabelText("Attribute 1 value"), "Ti");
    await userEvent.type(screen.getByLabelText("Attribute 2 name"), "massKg");
    await userEvent.type(screen.getByLabelText("Attribute 2 value"), "0.09");
    await userEvent.click(screen.getByRole("button", { name: "Save attributes" }));

    expect(onSave).toHaveBeenCalledWith({ material: "Ti", massKg: 0.09 });
  });

  it("reports errors instead of saving", async () => {
    const onSave = vi.fn();
    render(<AttributesEditor initial={{}} onSave={onSave} />);

    await userEvent.type(screen.getByLabelText("Attribute 1 value"), "orphan");
    await userEvent.click(screen.getByRole("button", { name: "Save attributes" }));

    expect(onSave).not.toHaveBeenCalled();
    expect(screen.getByRole("alert")).toHaveTextContent("Every attribute needs a name.");
  });
});
