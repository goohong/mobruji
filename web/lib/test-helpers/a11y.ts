/**
 * axe-core 기반 접근성 자동 검사 헬퍼.
 *
 * 이슈 #107 / PR #111:
 *   - rev 사이클 8 self-review에서 FE PR 8건 통틀어 a11y 측정 0건이라는 회고가 나옴.
 *   - 음역대 입력 select(옵션 49개), 카드 클릭 영역, 색상 difficulty 칩 등
 *     a11y 위험 영역이 많아 자동 가드가 필요.
 *
 * 정책:
 *   - serious/critical violations만 테스트를 fail 시킨다.
 *   - minor/moderate는 `console.warn`으로 로깅만 한다 (v0.2 단계).
 *   - happy-dom은 실제 브라우저 렌더링 엔진이 아니라 일부 시각 룰(`color-contrast` 등)이
 *     정확하게 평가되지 않을 수 있다. 그래도 라벨/role/aria 관련 위반은 충분히 잡힌다.
 *
 * 사용:
 *   ```ts
 *   const { container } = render(<MyComponent />);
 *   await expectNoA11yViolations(container);
 *   ```
 *
 * `expect.extend(...)`로 matcher를 만들지 않고 plain async assertion 함수로 둔다.
 * 이 헬퍼 외부에서 axe 결과를 다루는 코드는 없기 때문이다.
 */

import axe, { AxeResults, Result, RunOptions } from "axe-core";
import { expect } from "vitest";

/**
 * fail로 다룰 axe impact 레벨. axe-core impact: `minor` | `moderate` | `serious` | `critical`.
 */
const FAIL_IMPACTS: ReadonlyArray<NonNullable<Result["impact"]>> = [
  "serious",
  "critical",
];

/**
 * happy-dom에서 axe-core를 돌릴 때 비활성화하는 룰.
 *
 * `color-contrast`는 happy-dom의 getComputedStyle이 Tailwind 클래스로 부여된 색을
 * 픽셀 단위까지 정확히 계산해 주지 않아 false-positive가 잦다. 실제 브라우저 기반
 * Playwright E2E를 도입할 때 다시 켜는 편이 정확하다.
 *
 * `region`은 컴포넌트 단위 테스트에서 `<main>` 랜드마크 밖에 콘텐츠가 렌더되는 경우가
 * 잦아 (예: `SongCard` 단독 렌더) 컴포넌트 레벨에서는 의미가 약하다. 페이지 레벨
 * 테스트에서는 `enableRules`로 다시 켤 수 있다.
 */
const DEFAULT_DISABLED_RULES = ["color-contrast", "region"] as const;

export type ExpectNoA11yViolationsOptions = {
  /**
   * 이 테스트에서 추가로 비활성화하고 싶은 룰 ID.
   */
  disableRules?: ReadonlyArray<string>;
  /**
   * happy-dom에서도 의미 있는 룰을 명시적으로 활성화할 때 사용.
   * `DEFAULT_DISABLED_RULES`보다 우선한다.
   */
  enableRules?: ReadonlyArray<string>;
};

/**
 * 주어진 DOM 컨테이너에 대해 axe-core 검사를 수행.
 * serious/critical violations가 1건이라도 있으면 테스트를 fail한다.
 * minor/moderate violations는 `console.warn`으로 알림만 남긴다.
 */
export async function expectNoA11yViolations(
  container: Element,
  options: ExpectNoA11yViolationsOptions = {},
): Promise<void> {
  const results = await runAxe(container, options);
  const failing = results.violations.filter((violation) =>
    FAIL_IMPACTS.includes(violation.impact ?? "minor"),
  );
  const advisory = results.violations.filter(
    (violation) => !FAIL_IMPACTS.includes(violation.impact ?? "minor"),
  );

  if (advisory.length > 0) {
    // 테스트 fail은 아니지만 가시화. CI 로그에서 잡히게 한다.
    console.warn(
      `[a11y] minor/moderate violations (${advisory.length}):\n` +
        formatViolations(advisory),
    );
  }

  if (failing.length > 0) {
    const message =
      `[a11y] serious/critical violations (${failing.length}):\n` +
      formatViolations(failing);
    expect.fail(message);
  }
}

/**
 * axe-core 호출 자체를 노출해 다른 헬퍼에서 결과를 직접 다루고 싶을 때 쓴다.
 */
export async function runAxe(
  container: Element,
  options: ExpectNoA11yViolationsOptions = {},
): Promise<AxeResults> {
  const enabled = new Set(options.enableRules ?? []);
  const disabledRules = [...DEFAULT_DISABLED_RULES, ...(options.disableRules ?? [])]
    .filter((ruleId) => !enabled.has(ruleId));

  const rulesConfig: NonNullable<RunOptions["rules"]> = {};
  for (const ruleId of disabledRules) {
    rulesConfig[ruleId] = { enabled: false };
  }

  return axe.run(container, {
    // WCAG 2.1 AA + best-practices 기본 태그 셋. axe-core 기본값과 동일.
    runOnly: {
      type: "tag",
      values: ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "best-practice"],
    },
    rules: rulesConfig,
  });
}

function formatViolations(violations: Result[]): string {
  return violations
    .map((violation) => {
      const nodes = violation.nodes
        .slice(0, 3)
        .map((node) => `      target: ${node.target.join(" ")}`)
        .join("\n");
      const overflow =
        violation.nodes.length > 3
          ? `\n      ... ${violation.nodes.length - 3} more node(s)`
          : "";
      return (
        `  - [${violation.impact ?? "unknown"}] ${violation.id}: ${violation.help}\n` +
        `    help: ${violation.helpUrl}\n` +
        `${nodes}${overflow}`
      );
    })
    .join("\n");
}
