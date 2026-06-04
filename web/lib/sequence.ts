/**
 * P-D 모임 사회자 시퀀스(자리 단계 흐름) UI 헬퍼.
 *
 * "다 같이 즐길 곡" 모드(persona-expansion-social-emotional.md §2 P-D / §5-6, 이슈 #1601)의
 * 자리 단계(워밍업 → 고조 → 마무리) 표시 메타와 진행 순서를 모은다. 시퀀스 응답 자체는
 * be #1837(`POST /api/v1/recommendations/sequence`)이 단계별 가중으로 산출해 내려준다.
 * 시퀀스 타입(`SequenceStage` 등)의 SoT 는 `lib/api/recommendation.ts`.
 */

import type { SequenceStage } from "@/lib/api/recommendation";

/** 자리 단계 진행 순서: 워밍업 → 고조 → 마무리(BE SequenceStage ordinal 정합). */
export const SEQUENCE_STAGE_ORDER: readonly SequenceStage[] = [
  "WARMUP",
  "PEAK",
  "CLOSING",
] as const;

type StageMeta = {
  /** 단계 한국어 라벨(토글·헤더). */
  label: string;
  /** 단계 의도 한 줄(보조 설명). BE stageReason 부재 시 fallback. */
  description: string;
};

const STAGE_META: Record<SequenceStage, StageMeta> = {
  WARMUP: {
    label: "워밍업",
    description: "다 같이 편하게 자리를 엽니다.",
  },
  PEAK: {
    label: "고조",
    description: "신나는 곡으로 분위기를 끌어올립니다.",
  },
  CLOSING: {
    label: "마무리",
    description: "감성적인 곡으로 흐름을 닫습니다.",
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
