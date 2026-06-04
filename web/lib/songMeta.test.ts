/**
 * 곡 메타 표시 헬퍼 단위 가드 (이슈 #1715).
 *
 * formatLanguageLabel: ISO 639-1 코드 → 한국어 라벨, 매핑 불가/빈 값은 null(노출 생략).
 */

import { describe, expect, it } from "vitest";

import { formatLanguageLabel, formatMoodLabel } from "./songMeta";

describe("formatLanguageLabel", () => {
  it("매핑된 코드는 한국어 라벨로 변환한다", () => {
    expect(formatLanguageLabel("ko")).toBe("한국어");
    expect(formatLanguageLabel("en")).toBe("영어");
    expect(formatLanguageLabel("ja")).toBe("일본어");
  });

  it("대소문자·공백을 정규화한다", () => {
    expect(formatLanguageLabel(" KO ")).toBe("한국어");
    expect(formatLanguageLabel("En")).toBe("영어");
  });

  it("매핑에 없는 코드는 null 을 돌려준다(원문 노출 회피)", () => {
    expect(formatLanguageLabel("xx")).toBeNull();
    expect(formatLanguageLabel("MANUAL_SEED")).toBeNull();
  });

  it("빈 값(null/undefined/빈 문자열)은 null 을 돌려준다", () => {
    expect(formatLanguageLabel(null)).toBeNull();
    expect(formatLanguageLabel(undefined)).toBeNull();
    expect(formatLanguageLabel("")).toBeNull();
  });
});

describe("formatMoodLabel", () => {
  it("Mood enum 코드를 한국어 라벨로 변환한다 (#1764)", () => {
    expect(formatMoodLabel("UPBEAT")).toBe("신나는");
    expect(formatMoodLabel("CALM")).toBe("잔잔한");
    expect(formatMoodLabel("EMOTIONAL")).toBe("감성적인");
    expect(formatMoodLabel("POWERFUL")).toBe("파워풀한");
    expect(formatMoodLabel("GROOVY")).toBe("그루비한");
    expect(formatMoodLabel("NOSTALGIC")).toBe("추억의");
  });

  it("대소문자·공백을 정규화한다", () => {
    expect(formatMoodLabel(" upbeat ")).toBe("신나는");
  });

  it("미매핑 코드·빈 값은 null 을 돌려준다(원문 노출 회피)", () => {
    expect(formatMoodLabel("UNKNOWN_MOOD")).toBeNull();
    expect(formatMoodLabel(null)).toBeNull();
    expect(formatMoodLabel(undefined)).toBeNull();
    expect(formatMoodLabel("")).toBeNull();
  });
});
