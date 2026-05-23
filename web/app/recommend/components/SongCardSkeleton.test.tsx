/**
 * SongCardSkeleton 회귀 가드 (closes #515).
 *
 * 추천 결과 로딩 중 SongCard 자리에 표시되는 placeholder.
 * thumbnail(56px) + 텍스트 자리가 빠지면 카드 공간 인지가 깨진다.
 */

import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render } from "@testing-library/react";

import { SongCardSkeleton } from "./SongCard";
import { expectNoA11yViolations } from "@/lib/test-helpers/a11y";

afterEach(() => {
  cleanup();
});

describe("SongCardSkeleton", () => {
  it("렌더 시 li + thumbnail 자리(56px) + 텍스트 자리가 마운트된다", () => {
    const { container } = render(
      <ul>
        <SongCardSkeleton />
      </ul>,
    );
    const item = container.querySelector("li");
    expect(item).not.toBeNull();
    const thumbnail = item?.querySelector("div.h-14.w-14");
    expect(thumbnail).not.toBeNull();
    const pulses = item?.querySelectorAll(".animate-pulse") ?? [];
    expect(pulses.length).toBeGreaterThanOrEqual(8);
  });

  it("a11y serious/critical violations 없음", async () => {
    const { container } = render(
      <ul>
        <SongCardSkeleton />
      </ul>,
    );
    await expectNoA11yViolations(container);
  });
});
