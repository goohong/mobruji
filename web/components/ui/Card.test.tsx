/**
 * Card 컴포넌트 단위 테스트.
 */

import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

import { Card, CardBody, CardFooter, CardHeader } from "./Card";

afterEach(() => {
  cleanup();
});

describe("Card", () => {
  it("기본 Card는 div + ring 컨테이너로 렌더된다", () => {
    render(
      <Card data-testid="card">
        <p>안녕</p>
      </Card>,
    );

    const card = screen.getByTestId("card");
    expect(card.tagName).toBe("DIV");
    expect(card.className).toContain("rounded-2xl");
    expect(card.className).toContain("ring-1");
  });

  it("as='li' 폴리모픽 + Header/Body/Footer subcomponent를 조합할 수 있다", () => {
    render(
      <Card as="li" data-testid="card">
        <CardHeader>
          <h2>제목</h2>
        </CardHeader>
        <CardBody>본문</CardBody>
        <CardFooter>액션</CardFooter>
      </Card>,
    );

    const card = screen.getByTestId("card");
    expect(card.tagName).toBe("LI");
    expect(screen.getByRole("heading", { name: "제목" })).toBeInTheDocument();
    expect(screen.getByText("본문")).toBeInTheDocument();
    expect(screen.getByText("액션")).toBeInTheDocument();
  });
});
