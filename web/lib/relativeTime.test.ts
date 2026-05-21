/**
 * 상대 시간 포맷터 테스트 (closes #134).
 *
 * 범위:
 *  - 분 미만 / 분 / 시간 / 어제 / 그 이전 분기 모두 확인.
 *  - 잘못된 입력 fallback.
 *  - 미래 시각 fallback.
 */

import { describe, expect, it } from "vitest";

import { formatRelativeKorean } from "./relativeTime";

const NOW = new Date("2026-05-21T15:00:00Z");

describe("formatRelativeKorean", () => {
  it("1분 미만은 '방금 전'", () => {
    const target = new Date(NOW.getTime() - 30 * 1000);
    expect(formatRelativeKorean(target, NOW)).toBe("방금 전");
  });

  it("1~59분은 'N분 전'", () => {
    const target = new Date(NOW.getTime() - 10 * 60 * 1000);
    expect(formatRelativeKorean(target, NOW)).toBe("10분 전");
  });

  it("1~23시간은 'N시간 전'", () => {
    const target = new Date(NOW.getTime() - 3 * 60 * 60 * 1000);
    expect(formatRelativeKorean(target, NOW)).toBe("3시간 전");
  });

  it("24~48시간은 '어제 H시'", () => {
    const target = new Date(NOW.getTime() - 30 * 60 * 60 * 1000);
    const expectedHour = target.getHours();
    expect(formatRelativeKorean(target, NOW)).toBe(`어제 ${expectedHour}시`);
  });

  it("48시간 초과는 'M월 D일'", () => {
    const target = new Date("2026-05-10T08:00:00Z");
    const expected = `${target.getMonth() + 1}월 ${target.getDate()}일`;
    expect(formatRelativeKorean(target, NOW)).toBe(expected);
  });

  it("ISO 문자열 입력도 동일하게 처리한다", () => {
    const iso = new Date(NOW.getTime() - 5 * 60 * 1000).toISOString();
    expect(formatRelativeKorean(iso, NOW)).toBe("5분 전");
  });

  it("미래 시각(now 보다 뒤)은 '방금 전'으로 fallback", () => {
    const target = new Date(NOW.getTime() + 60 * 1000);
    expect(formatRelativeKorean(target, NOW)).toBe("방금 전");
  });

  it("잘못된 입력은 '알 수 없음'을 반환한다", () => {
    expect(formatRelativeKorean("invalid", NOW)).toBe("알 수 없음");
  });
});
