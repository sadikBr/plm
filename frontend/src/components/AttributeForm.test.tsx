import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it } from "vitest";
import { ApiError } from "../api/client";
import type { AttributeDefinition, Attributes, LayoutGroup } from "../api/queries";
import { fakeApi } from "../test/fakeApi";
import { attributeRoutes, enumerations, partAttributes, partLayout } from "../test/fixtures";
import {
  AttributeForm,
  AttributeValues,
  defaultValues,
  fieldErrors,
  formatAttribute,
  fromLocalInput,
  pickLabel,
  toLocalInput,
} from "./AttributeForm";

const defs = partAttributes as unknown as AttributeDefinition[];

const layoutGroups = partLayout().groups as LayoutGroup[];

function Harness({
  initial = {},
  onValues,
  groups,
  errors,
}: {
  initial?: Attributes;
  onValues: (v: Attributes) => void;
  groups?: LayoutGroup[];
  errors?: Record<string, string>;
}) {
  const [values, setValues] = useState(initial);
  return (
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AttributeForm
        definitions={defs}
        groups={groups}
        errors={errors}
        values={values}
        onChange={(v) => {
          setValues(v);
          onValues(v);
        }}
      />
    </QueryClientProvider>
  );
}

describe("helpers", () => {
  it("picks the label for the user's language, falling back to English", () => {
    expect(pickLabel({ en: "Material", fr: "Matériau" })).toBe("Material"); // jsdom is en-US
    expect(pickLabel({ de: "Masse" })).toBe("Masse");
    expect(pickLabel(undefined, "mass")).toBe("mass");
  });

  it("round-trips timestamps through datetime-local inputs", () => {
    const iso = "2026-11-20T10:15:00.000Z";
    expect(fromLocalInput(toLocalInput(iso))).toBe(iso);
    expect(toLocalInput("not a date")).toBe("");
  });

  it("formats values with enumeration labels and units", () => {
    const map = new Map(enumerations.map((e) => [e.id, e]));
    const source = defs.find((d) => d.name === "source");
    expect(formatAttribute(source, "buy", map as never)).toBe("Buy");
    expect(formatAttribute(undefined, { value: 1.5, unit: "kg" })).toBe("1.5 kg");
    expect(formatAttribute(undefined, true)).toBe("Yes");
    expect(formatAttribute(undefined, null)).toBe("—");
  });

  it("collects defaults of active attributes", () => {
    expect(defaultValues(defs)).toEqual({ source: "make", endItem: false });
  });

  it("extracts per-field errors only from INVALID_ATTRIBUTES problems", () => {
    const invalid = new ApiError(422, {
      code: "INVALID_ATTRIBUTES",
      details: { errors: [{ attribute: "mass", code: "OUT_OF_RANGE", message: "Mass must be at least 0 kg" }] },
    });
    expect(fieldErrors(invalid)).toEqual({ mass: "Mass must be at least 0 kg" });
    expect(fieldErrors(new ApiError(409, { code: "ALREADY_CHECKED_OUT" }))).toEqual({});
  });
});

describe("AttributeForm", () => {
  it("renders an input per data type and emits typed values", async () => {
    fakeApi(attributeRoutes);
    let latest: Attributes = {};
    render(<Harness onValues={(v) => (latest = v)} />);

    // Enumeration: labels shown, inactive values hidden.
    const source = await screen.findByLabelText("Source");
    expect(await screen.findByRole("option", { name: "Buy" })).toBeInTheDocument();
    expect(screen.queryByRole("option", { name: "Legacy" })).not.toBeInTheDocument();
    await userEvent.selectOptions(source, "buy");

    await userEvent.type(screen.getByLabelText("Mass"), "12");
    expect(await screen.findByRole("option", { name: "g" })).toBeInTheDocument();
    await userEvent.selectOptions(screen.getByLabelText("Mass unit"), "g");
    expect(screen.queryByRole("option", { name: "m" })).not.toBeInTheDocument(); // other dimensions excluded

    await userEvent.type(screen.getByLabelText("Released at"), "2026-11-20T09:30");

    expect(latest).toEqual({ source: "buy", mass: { value: 12, unit: "g" }, releasedAt: fromLocalInput("2026-11-20T09:30") });
  });

  it("clears a value when the input is emptied", async () => {
    fakeApi(attributeRoutes);
    let latest: Attributes = {};
    render(<Harness initial={{ material: "AL" }} onValues={(v) => (latest = v)} />);

    await userEvent.clear(await screen.findByLabelText("Material"));

    expect(latest).toEqual({});
  });

  it("groups and orders fields by the layout, and leaves out attributes it hides", async () => {
    fakeApi(attributeRoutes);
    let latest: Attributes = {};
    // The layout hides nothing explicitly, but only lists some attributes: the rest are not rendered.
    const groups = layoutGroups.filter((g) => g.key !== "more");
    render(<Harness groups={groups} initial={{ releasedAt: "2026-01-01T00:00:00Z" }} onValues={(v) => (latest = v)} />);

    const general = await screen.findByRole("group", { name: "General" });
    const labels = within(general)
      .getAllByText(/Material|Mass|Source/, { selector: "label" })
      .map((l) => l.textContent);
    expect(labels).toEqual(["Material", "Mass", "Source"]);
    expect(within(screen.getByRole("group", { name: "Structure and tracking" })).getByLabelText("End item")).toBeInTheDocument();
    expect(screen.queryByLabelText("Released at")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Legacy code")).not.toBeInTheDocument();

    // Values of fields the layout doesn't show are kept.
    await userEvent.type(screen.getByLabelText("Material"), "Ti");
    expect(latest).toEqual({ releasedAt: "2026-01-01T00:00:00Z", material: "Ti" });
  });

  it("shows errors for attributes the layout doesn't show above the form", async () => {
    fakeApi(attributeRoutes);
    render(<Harness groups={layoutGroups.slice(0, 1)} errors={{ material: "Too long", endItem: "Required" }} onValues={() => {}} />);

    expect(await screen.findByText("Too long")).toBeInTheDocument();
    expect(screen.getByText("End item: Required")).toHaveClass("banner");
  });
});

describe("AttributeValues", () => {
  const renderValues = (values: Attributes, groups?: LayoutGroup[]) =>
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <AttributeValues definitions={defs} values={values} groups={groups} />
      </QueryClientProvider>,
    );

  it("shows one titled section per layout group, plus values that no longer have an active attribute", async () => {
    fakeApi(attributeRoutes);
    renderValues({ material: "AL6061", legacyCode: "X-1", importedFrom: "old-plm" }, layoutGroups);

    const general = await screen.findByRole("region", { name: "General" });
    expect(within(general).getByText("AL6061")).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "More attributes" })).toBeInTheDocument();
    const other = screen.getByRole("region", { name: "Other values" });
    expect(within(other).getByText("Legacy code")).toBeInTheDocument();
    expect(within(other).getByText("old-plm")).toBeInTheDocument();
  });

  it("needs no headings without a layout", async () => {
    fakeApi(attributeRoutes);
    renderValues({ material: "AL6061" });

    expect(await screen.findByText("AL6061")).toBeInTheDocument();
    expect(screen.queryByRole("region")).not.toBeInTheDocument();
  });
});

describe("Dependent value lists", () => {
  it("offer only the values allowed with the parent's value", async () => {
    fakeApi(attributeRoutes);
    const source = defs.find((d) => d.name === "source")!;
    const child = {
      ...source,
      id: "dep",
      name: "supplierClass",
      labels: { en: "Supplier class" },
      defaultValue: null,
      dependsOn: "source",
      // The fixture list is reused for the child: only "buy" may follow "make".
      valueFilter: { make: ["buy"] },
    } as AttributeDefinition;
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <AttributeForm definitions={[source, child]} values={{ source: "make" }} onChange={() => {}} />
      </QueryClientProvider>,
    );

    const select = await screen.findByLabelText("Supplier class");
    await screen.findAllByRole("option", { name: "Buy" });
    expect(within(select).getByRole("option", { name: "Buy" })).toBeInTheDocument();
    expect(within(select).queryByRole("option", { name: "Make" })).not.toBeInTheDocument();
  });
});
