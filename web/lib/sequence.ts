/**
 * P-D 모임 사회자 시퀀스(자리 단계 흐름) UI 헬퍼.
 *
 * "다 같이 즐길 곡" 모드(persona-expansion-social-emotional.md §2 P-D / §5-6, 이슈 #1601)의
 * 자리 단계(도입 → 고조 → 마무리) 표시 메타와, be #1599 미머지 시 기존 추천을 단계별로
 * 묶는 client placeholder fallback 을 모은다. 시퀀스 타입(`SequenceStage` 등)의 SoT 는
 * `lib/api/recommendation.ts`.
 */

import type {
  RecommendationPersona,
  RecommendationResponse,
  SequenceRecommendationResponse,
  SequenceStage,
  SequenceStageBundle,
} from "@/lib/api/recommendation";

/** P-D 모임 사회자 모드 — "이 자리 다 같이 즐기게 하고 싶다"(spec §2). */
export const HOST_PERSONA: RecommendationPersona = "P-D";

/** 자리 단계 진행 순서: 도입 → 고조 → 마무리. */
export const SEQUENCE_STAGE_ORDER: readonly SequenceStage[] = [
  "INTRO",
  "PEAK",
  "FINALE",
] as const;

type StageMeta = {
  /** 단계 한국어 라벨(토글·헤더). */
  label: string;
  /** 단계 의도 한 줄(보조 설명). */
  description: string;
};

const STAGE_META: Record<SequenceStage, StageMeta> = {
  INTRO: {
    label: "도입",
    description: "누구나 아는 곡으로 자리를 엽니다.",
  },
  PEAK: {
    label: "고조",
    description: "신나는 곡으로 분위기를 끌어올립니다.",
  },
  FINALE: {
    label: "마무리",
    description: "다 같이 부르는 곡으로 흐름을 닫습니다.",
  },
};

/** 단계 표시 메타(라벨/설명)를 돌려준다. */
export function stageMeta(stage: SequenceStage): StageMeta {
  return STAGE_META[stage];
}

/**
 * 다음 자리 단계를 돌려준다(마지막 단계면 `null`).
 *
 * "한 곡 부른 뒤 다음(고조)로 흐름 진행"(이슈 요구사항)의 진행 버튼 라벨/동작 근거.
 */
export function nextStage(stage: SequenceStage): SequenceStage | null {
  const index = SEQUENCE_STAGE_ORDER.indexOf(stage);
  if (index < 0 || index >= SEQUENCE_STAGE_ORDER.length - 1) {
    return null;
  }
  return SEQUENCE_STAGE_ORDER[index + 1];
}

/**
 * 기존 단일 추천 응답을 3단계 시퀀스로 묶는 client placeholder fallback.
 *
 * be #1599(시퀀스 엔드포인트)가 머지되기 전에도 P-D 모드 UX(자리 단계 토글 + 단계별 뷰)를
 * 검증·시연할 수 있게, 일반 추천 곡 리스트를 도입/고조/마무리 3등분으로 결정성 있게
 * 분배한다([[feedback-be-fe-parallel]]). BE 가 단계별 가중을 산출하는 정식 응답을 내려주면
 * 호출 측은 그 응답을 우선 사용하고 이 fallback 은 쓰이지 않는다.
 *
 * 분배 규칙: 입력 순서를 보존한 채 앞에서부터 INTRO/PEAK/FINALE 로 균등 3등분(나머지는
 * 앞 단계부터 1곡씩). 곡이 3개 미만이면 일부 단계는 빈 묶음이 된다.
 */
export function deriveSequenceFallback(
  base: RecommendationResponse,
): SequenceRecommendationResponse {
  const songs = base.recommendations;
  const total = songs.length;
  const baseSize = Math.floor(total / SEQUENCE_STAGE_ORDER.length);
  const remainder = total % SEQUENCE_STAGE_ORDER.length;

  const stages: SequenceStageBundle[] = [];
  let cursor = 0;
  for (let i = 0; i < SEQUENCE_STAGE_ORDER.length; i += 1) {
    // 나머지 곡은 앞 단계부터 1곡씩 더 배정 — 결정적 분배.
    const size = baseSize + (i < remainder ? 1 : 0);
    stages.push({
      stage: SEQUENCE_STAGE_ORDER[i],
      songs: songs.slice(cursor, cursor + size),
    });
    cursor += size;
  }

  return {
    requestId: base.requestId,
    persona: HOST_PERSONA,
    stages,
  };
}
