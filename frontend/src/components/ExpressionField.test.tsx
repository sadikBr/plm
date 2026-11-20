import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState } from "react";
import { describe, expect, it } from "vitest";
import { fakeApi } from "../test/fakeApi";
import { ExpressionField } from "./ExpressionField";

function Harness() {
  const [v, setV] = useState("");
  return <ExpressionField id="e" label="Visible when" value={v} onChange={setV} />;
}

function renderField() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={qc}>
      <Harness />
    </QueryClientProvider>,
  );
}

describe("ExpressionField", () => {
  it("checks the expression as you type and says where it's wrong", async () => {
    fakeApi({
      "POST /api/v1/expressions/check": async (req) => {
        const { expression } = (await req.json()) as { expression: string };
        return expression.endsWith("==")
          ? { valid: false, problems: [{ message: "Syntax error: mismatched input", line: 1, column: 15 }] }
          : { valid: true, problems: [] };
      },
    });
    renderField();

    await userEvent.type(screen.getByLabelText("Visible when"), "object.state ==");
    expect(await screen.findByText(/Line 1, column 15: Syntax error/)).toBeInTheDocument();
    expect(screen.getByLabelText("Visible when")).toHaveAttribute("aria-invalid", "true");

    await userEvent.type(screen.getByLabelText("Visible when"), " 'RELEASED'");
    expect(await screen.findByText("Valid")).toBeInTheDocument();
  });

  it("tries the expression on an object found by number", async () => {
    fakeApi({
      "POST /api/v1/expressions/check": () => ({ valid: true, problems: [] }),
      "GET /api/v1/objects": () => [{ id: "o1", number: "0000000042" }],
      "POST /api/v1/objects/:id/expression-model/evaluate": () => ({ value: true, error: null, problems: [] }),
    });
    renderField();

    await userEvent.type(screen.getByLabelText("Visible when"), "object.state == 'RELEASED'");
    await screen.findByText("Valid");
    await userEvent.type(screen.getByLabelText("Try on object"), "42");
    await userEvent.click(screen.getByRole("button", { name: "Try" }));
    expect(await screen.findByRole("status")).toHaveTextContent("0000000042: true");
  });
});
