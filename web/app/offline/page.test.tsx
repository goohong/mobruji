/**
 * Service Worker offline fallback 페이지 회귀 가드.
 *
 * 이슈 #605:
 *   - `app/offline/page.tsx`는 네트워크 단절 시 SW가 응답하는 fallback이라
 *     실제 브라우저에서 자주 노출되지 않아 회귀가 늦게 발견될 위험이 큼.
 *   - 메시지 변경/마크업 사고를 단위 테스트로 가드한다.
 *
 * 가드 범위:
 *   - heading + 안내 문구 렌더
 *   - `<main aria-labelledby>`가 실제 heading id와 연결되는지
 *   - axe-core serious/critical 위반 0건
 */

import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

import { expectNoA11yViolations } from "@/lib/test-helpers/a11y";

import OfflinePage from "./page";

afterEach(() => {
  cleanup();
});

describe("OfflinePage (app/offline/page.tsx)", () => {
  it("오프라인 안내 heading과 본문이 렌더된다", () => {
    render(<OfflinePage />);

    const heading = screen.getByRole("heading", {
      name: /오프라인 상태입니다/,
    });
    expect(heading).toBeInTheDocument();
    expect(heading.id).toBe("offline-heading");

    expect(
      screen.getByText(/네트워크 연결을 확인한 뒤 새로고침 해주세요\./),
    ).toBeInTheDocument();
  });

  it("main 랜드마크가 heading과 aria-labelledby로 연결된다", async () => {
    const { container } = render(<OfflinePage />);

    const main = screen.getByRole("main");
    expect(main).toHaveAttribute("aria-labelledby", "offline-heading");

    await expectNoA11yViolations(container, { enableRules: ["region"] });
  });
});
