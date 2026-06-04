/**
 * 추천 의도 페르소나(P-E 안전곡 등) UI 헬퍼.
 *
 * 추천 화면의 "의도 모드" 선택 + 결과 카드의 페르소나 사유 노출을 위한 표시 로직을 모은다
 * (persona-expansion-social-emotional.md §5-6, 이슈 #1600). BE 와 매칭되는 페르소나 식별자 타입
 * (`RecommendationPersona`)은 `lib/api/recommendation.ts` SoT.
 *
 * 1차(#1600)는 `P-E` 안전곡 모드만 web 에서 노출한다. P-D/P-F/P-G 는 후속 이슈.
 *
 * be #1840 머지로 안전곡의 "안심 포인트"(쉬운 이유) 사유는 BE `/safe` 응답이 곡별로 항상
 * 채워 내려준다(`SafeSongResponse.safetyReason`, 난이도 미상·음역 부재 곡도 graceful 한 기본
 * 사유). client 가 곡 난이도로 사유를 추정하던 fallback·정합 가드는 BE 산정으로 흡수됐다 —
 * web 은 BE 가 내려준 사유 텍스트를 "안심 포인트" 라벨과 함께 그대로 노출하면 된다.
 */

import type { RecommendationPersona } from "@/lib/api/recommendation";

/** P-E 안전곡 모드 — "안 망하고 무사히 넘기고 싶다"(spec §2). */
export const SAFE_SONG_PERSONA: RecommendationPersona = "P-E";

/** P-E 안전곡 모드 결과 카드의 사유 라벨 — "쉬운 이유"를 "안심 포인트"로 옷 입힌다. */
export const SAFE_SONG_REASON_LABEL = "안심 포인트";
