/**
 * 추천 의도 페르소나(P-E 안전곡 등) UI 헬퍼.
 *
 * 추천 화면의 "의도 모드" 선택 + 결과 카드의 페르소나 사유 노출을 위한 표시 로직을
 * 모은다(persona-expansion-social-emotional.md §5-6, 이슈 #1600). BE 와 매칭되는
 * 페르소나 식별자 타입(`RecommendationPersona`)은 `lib/api/recommendation.ts` SoT.
 *
 * 1차(#1600)는 `P-E` 안전곡 모드만 web 에서 노출한다. P-D/P-F/P-G 는 후속 이슈.
 */

import type {
  RecommendationPersona,
  RecommendedSongResponse,
} from "@/lib/api/recommendation";
import { resolveSongDifficulty } from "@/lib/difficulty";

/** P-E 안전곡 모드 — "안 망하고 무사히 넘기고 싶다"(spec §2). */
export const SAFE_SONG_PERSONA: RecommendationPersona = "P-E";

/**
 * 페르소나별 결과 카드 사유 라벨.
 *
 * P-E 안전곡 모드는 "안심 포인트"(쉬운 이유)로 옷 입힌다. 그 외 페르소나는 사유 텍스트만
 * 노출하면 충분하므로 중립 라벨을 돌려준다.
 */
export function personaReasonLabel(persona: RecommendationPersona): string {
  if (persona === SAFE_SONG_PERSONA) {
    return "안심 포인트";
  }
  return "이 곡을 고른 이유";
}

/**
 * BE `personaReason` 미보유 시 사용할 client-side fallback 사유.
 *
 * BE #1598 머지 전에도 P-E 모드 선택 시 "안심 포인트"를 보여주기 위해(병행 가능,
 * placeholder/null fallback) 곡 난이도를 근거로 짧은 사유를 만든다. 난이도 정보가 없으면
 * `null` 을 돌려주고, 호출 측은 그 경우 페르소나 사유를 생략한다.
 */
export function buildPersonaFallbackReason(
  persona: RecommendationPersona,
  song: RecommendedSongResponse["song"],
): string | null {
  if (persona !== SAFE_SONG_PERSONA) {
    return null;
  }
  const difficulty = resolveSongDifficulty(song);
  switch (difficulty) {
    case "EASY":
      return "음역이 넉넉하고 난이도가 낮아 부담 없이 부를 수 있어요.";
    case "NORMAL":
      return "무난한 난이도라 큰 무리 없이 소화할 수 있어요.";
    default:
      return null;
  }
}

/**
 * 결과 카드에 노출할 페르소나 사유(라벨 + 텍스트)를 해석한다.
 *
 * 우선순위: BE 가 내려준 `item.personaReason` → 없으면 활성 페르소나 기반 client fallback.
 * 어느 쪽도 없으면 `null` 을 돌려주고 카드는 페르소나 사유 줄을 그리지 않는다.
 *
 * `activePersona` 는 사용자가 화면에서 고른 의도 모드다 — BE 응답에 `persona` 가 아직
 * 없을 때 fallback 라벨/사유의 근거가 된다.
 */
export function resolvePersonaReason(
  item: RecommendedSongResponse,
  activePersona: RecommendationPersona | null,
): { label: string; text: string } | null {
  const persona = item.persona ?? activePersona;
  if (!persona) {
    return null;
  }
  const text =
    item.personaReason ?? buildPersonaFallbackReason(persona, item.song);
  if (!text) {
    return null;
  }
  return { label: personaReasonLabel(persona), text };
}
