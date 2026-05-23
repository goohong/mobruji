/** Providers 회귀 가드 (closes #612): children + QueryClientProvider + ADR 0004 옵션. */
import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { useQueryClient } from "@tanstack/react-query";
import { Providers } from "./providers";

afterEach(() => cleanup());

function OptionsProbe() {
  const queries = useQueryClient().getDefaultOptions().queries;
  return (
    <div data-testid="probe">
      {String(queries?.retry)}|{String(queries?.refetchOnWindowFocus)}|
      {String(queries?.staleTime)}
    </div>
  );
}

describe("Providers", () => {
  it("children을 그대로 렌더한다", () => {
    render(
      <Providers>
        <span data-testid="child">hello</span>
      </Providers>,
    );
    expect(screen.getByTestId("child")).toHaveTextContent("hello");
  });

  it("QueryClientProvider 컨텍스트와 ADR 0004 기본 옵션을 노출한다", () => {
    render(
      <Providers>
        <OptionsProbe />
      </Providers>,
    );
    expect(screen.getByTestId("probe")).toHaveTextContent("1|false|30000");
  });
});
