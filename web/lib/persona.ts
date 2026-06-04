/**
 * 추천 의도 페르소나(P-E 안전곡 등) UI 헬퍼.
 *
 * 추천 화면의 "의도 모드" 선택 + 결과 카드의 페르소나 사유 노출을 위한 표시 로직을 모은다
 * (persona-expansion-social-emotional.md §5-6, 이슈 #1600). BE 와 매칭되는 페르소나 식별자 타입
 * (`RecommendationPersona`)은 `lib/api/recommendation.ts` SoT.
 *
 * web 노출 모드는 `P-E` 안전곡(#1600)·`P-F` 과시·킬링파트(#1844). P-D 시퀀스는 별도 화면, P-G 는 후속 이슈.
 *
 * 페르소나별 결과 카드 사유는 BE 전용 엔드포인트 응답이 곡별로 항상 채워 내려준다 — 안전곡은
 * "안심 포인트"(쉬운 이유, `/safe` `safetyReason`, be #1840), 과시는 "킬링파트 안내"(임팩트 구간,
 * `/showoff` `killingPartReason`, be #1843). 둘 다 메타 미상 곡도 graceful 한 기본 사유라 client 가
 * 사유를 추정할 필요 없이 BE 가 내려준 텍스트를 페르소나 라벨과 함께 그대로 노출하면 된다.
 */

import type { RecommendationPersona } from "@/lib/api/recommendation";

/** P-E 안전곡 모드 — "안 망하고 무사히 넘기고 싶다"(spec §2). */
export const SAFE_SONG_PERSONA: RecommendationPersona = "P-E";

/** P-E 안전곡 모드 결과 카드의 사유 라벨 — "쉬운 이유"를 "안심 포인트"로 옷 입힌다. */
export const SAFE_SONG_REASON_LABEL = "안심 포인트";

/** P-F 과시·킬링파트 모드 — "고음 질러 박수받고 싶다"(spec §2, 안전곡의 반대축). */
export const SHOWOFF_SONG_PERSONA: RecommendationPersona = "P-F";

/** P-F 과시 모드 결과 카드의 사유 라벨 — 임팩트 구간을 "킬링파트"로 옷 입힌다. */
export const SHOWOFF_SONG_REASON_LABEL = "킬링파트";
