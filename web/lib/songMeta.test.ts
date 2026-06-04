/**
 * 곡 메타 표시 헬퍼 단위 가드 (이슈 #1715).
 *
 * formatLanguageLabel: ISO 639-1 코드 → 한국어 라벨, 매핑 불가/빈 값은 null(노출 생략).
 */

import { describe, expect, it } from "vitest";

import { formatLanguageLabel } from "./songMeta";

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
