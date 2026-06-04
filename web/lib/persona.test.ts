/**
 * 페르소나 UI 헬퍼 단위 가드 (이슈 #1600).
 *
 * be #1840 머지로 안전곡 "안심 포인트" 사유는 BE `/safe` 응답이 곡별로 내려준다 — web 의 client
 * 사유 추정·정합 가드(구 buildPersonaFallbackReason / resolvePersonaReason)는 제거됐다. 남은 것은
 * 페르소나 식별자 상수와 결과 카드 사유 라벨뿐이다.
 */

import { describe, expect, it } from "vitest";

import { SAFE_SONG_PERSONA, SAFE_SONG_REASON_LABEL } from "./persona";

describe("persona 헬퍼", () => {
  it("SAFE_SONG_PERSONA 는 P-E 식별자다", () => {
    expect(SAFE_SONG_PERSONA).toBe("P-E");
  });

  it("SAFE_SONG_REASON_LABEL 은 '안심 포인트' 라벨이다", () => {
    expect(SAFE_SONG_REASON_LABEL).toBe("안심 포인트");
  });
});
