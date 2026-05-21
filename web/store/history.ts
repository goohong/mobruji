/**
 * 추천 히스토리 저장소 (클라이언트 전용, closes #134).
 *
 * 배경:
 *   - "다른 곡 추천받기"(PR #85)로 사용자는 한 세션에서 N번의 추천 결과를 받는다.
 *     이전 결과는 곧바로 버려지므로 "방금 받은 추천 다시 보기"가 불가능했다.
 *   - 백엔드 추가 없이 zustand persist만으로 클라이언트 측 히스토리를 제공한다.
 *     (BE 작업 없이 v1 가치를 빠르게 검증하고, 추후 server-side history로
 *     마이그레이션 여지를 남긴다.)
 *
 * 정책:
 *   - 최대 20건 보관(FIFO). v1 PoC 기준 한 사람이 검토하는 분량으로 충분하다.
 *   - `requestedAt`은 ISO8601 문자열로 보관 — JSON serialize/parse 후에도
 *     Date 객체로의 변환을 호출 측에서 결정할 수 있게 한다.
 *   - `id`는 `requestId`(BE 발급) + 우연 충돌 방지용 suffix 형태로 클라이언트가
 *     자체 발급한다. 같은 requestId가 여러 번 push되는 경우는 빈도가 낮지만
 *     이론적으로 가능하므로 store 자체 ID를 둔다.
 *   - 누적 제외 곡 ID(`excludedSongIds`)도 함께 스냅샷 해 둔다. "다시 보기" 시점에
 *     세션 store가 가지고 있는 누적 상태와 다를 수 있어서다.
 *
 * 보안 (rev 사이클 9 / PR #129):
 *   - 본 store는 localStorage에 저장된다 — 사용자 본인 브라우저만 접근.
 *   - `safeLog` 통해 외부 로깅 시에는 자동 PII 마스킹된다.
 */

"use client";

import { create } from "zustand";
import { persist, createJSONStorage } from "zustand/middleware";

import type { RecommendedSongResponse } from "@/lib/api/recommendation";

/**
 * 히스토리 최대 보관 건수.
 *
 * 20건 = "다시 추천" 버튼을 20번 누른 분량. UX 검토 결과 한 화면에서 카드 형태로
 * 스크롤하기에 부담스럽지 않은 상한.
 */
export const MAX_HISTORY_ENTRIES = 20;

export type RecommendationHistoryEntry = {
  /** 클라이언트 발급 ID — 삭제/복원 시 unique key 로 사용. */
  id: string;
  /** 추천을 받은 시점. ISO8601 UTC. */
  requestedAt: string;
  /** 이 추천을 만든 voiceRangeId — 같은 음역대 그룹핑에 사용 가능. */
  voiceRangeId: number | null;
  /** BE가 발급한 requestId — 본 응답을 다시 조회할 때 유용. */
  requestId: number;
  /** 추천된 곡 리스트(BE 응답 원본). */
  songs: RecommendedSongResponse[];
  /** 추천 요청 시 사용한 누적 제외 곡 ID 스냅샷. */
  excludedSongIds: number[];
  /**
   * 추천 시점의 음역대 스냅샷 (closes #170).
   *
   * - 기존 entry 와의 호환을 위해 optional 로 두며, 누락 시 진행 추적 카드에서 무시된다.
   * - voiceRangeId 만으로는 측정값을 복원할 수 없어 진행 그래프를 그릴 수 없으므로
   *   onSuccess 시점의 lowestNoteMidi/highestNoteMidi/sourceMethod 를 그대로 보관한다.
   */
  voiceRangeLowMidi?: number;
  voiceRangeHighMidi?: number;
  voiceRangeSourceMethod?: "SELF_REPORT" | "OCTAVE_PICK" | "MIC_MEASURE";
};

/**
 * push 시 호출자가 채우는 필드. `id` 와 `requestedAt`은 store가 자동 발급한다.
 *
 * 자동 발급으로 두는 이유: 추천 onSuccess 핸들러 한 곳에서만 호출되므로 호출 측이
 * id 충돌/시간 조작을 신경쓰지 않도록 강제한다.
 */
export type RecommendationHistoryInput = Omit<
  RecommendationHistoryEntry,
  "id" | "requestedAt"
>;

type HistoryState = {
  recommendations: RecommendationHistoryEntry[];
  appendRecommendation: (input: RecommendationHistoryInput) => void;
  removeRecommendation: (id: string) => void;
  clearHistory: () => void;
};

function generateEntryId(): string {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) {
    return crypto.randomUUID();
  }
  return `hist_${Date.now().toString(36)}_${Math.random()
    .toString(36)
    .slice(2, 10)}`;
}

function nowIso(): string {
  return new Date().toISOString();
}

export const useHistoryStore = create<HistoryState>()(
  persist(
    (set) => ({
      recommendations: [],
      appendRecommendation: (input) => {
        const entry: RecommendationHistoryEntry = {
          id: generateEntryId(),
          requestedAt: nowIso(),
          ...input,
        };
        set((state) => {
          // 새 항목을 앞에 prepend (최신순). 상한 초과 시 끝에서 자른다 — 가장 오래된
          // 항목부터 만료된다. push + slice 보다 prepend + slice 가 "최신 위" UX와
          // 직관적으로 일치한다.
          const next = [entry, ...state.recommendations];
          if (next.length <= MAX_HISTORY_ENTRIES) {
            return { recommendations: next };
          }
          return { recommendations: next.slice(0, MAX_HISTORY_ENTRIES) };
        });
      },
      removeRecommendation: (id) => {
        set((state) => ({
          recommendations: state.recommendations.filter(
            (entry) => entry.id !== id,
          ),
        }));
      },
      clearHistory: () => set({ recommendations: [] }),
    }),
    {
      name: "mobruji-history",
      storage: createJSONStorage(() => localStorage),
    },
  ),
);
