/**
 * a11y 헬퍼의 자체 sanity 테스트.
 *
 * - 정상적인 마크업은 위반 없이 통과해야 한다.
 * - 명백한 위반(이미지 alt 누락, label 없는 input)은 serious/critical로 잡혀야 한다.
 *
 * 헬퍼 자체가 잘못되면 모든 페이지 a11y 테스트가 거짓 통과/실패를 낼 수 있어
 * 격리된 회귀 가드를 둔다.
 */

import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render } from "@testing-library/react";

import { expectNoA11yViolations, runAxe } from "./a11y";

afterEach(() => {
  cleanup();
});

describe("expectNoA11yViolations", () => {
  it("정상 마크업은 위반 없이 통과한다", async () => {
    const { container } = render(
      <main>
        <h1>제목</h1>
        <p>본문 내용입니다.</p>
        <button type="button">클릭</button>
      </main>,
    );
    await expectNoA11yViolations(container);
  });

  it("label 없는 input은 위반을 잡아낸다", async () => {
    // 의도적으로 위반을 만든 마크업 — 헬퍼가 실제로 fail을 만드는지 검증.
    const { container } = render(
      <div>
        <input type="text" />
      </div>,
    );
    await expect(expectNoA11yViolations(container)).rejects.toThrow(/a11y/);
  });

  it("runAxe는 axe-core 원본 결과를 그대로 돌려준다", async () => {
    const { container } = render(
      <main>
        <h1>OK</h1>
      </main>,
    );
    const results = await runAxe(container);
    expect(results).toHaveProperty("violations");
    expect(Array.isArray(results.violations)).toBe(true);
  });
});
