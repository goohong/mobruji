/**
 * 추천 API 클라이언트.
 *
 * BE 컨트롤러 `com.mobruji.recommendation.RecommendationController`와 1:1 매칭:
 *   POST /api/v1/recommendations        → create (201)
 *   GET  /api/v1/recommendations/{id}   → read
 *
 * Mood enum, MusicalKey enum은 BE와 동일한 SCREAMING_SNAKE 문자열로 직렬화된다.
 * 자세한 응답 구조는 docs/features/recommendation-algorithm-v1.md §5-2.
 */

import { apiFetch } from "./client";

export type Mood =
  | "UPBEAT"
  | "CALM"
  | "EMOTIONAL"
  | "POWERFUL"
  | "GROOVY"
  | "NOSTALGIC";

export type MusicalKey =
  | "C_MAJOR"
  | "C_SHARP_MAJOR"
  | "D_MAJOR"
  | "D_SHARP_MAJOR"
  | "E_MAJOR"
  | "F_MAJOR"
  | "F_SHARP_MAJOR"
  | "G_MAJOR"
  | "G_SHARP_MAJOR"
  | "A_MAJOR"
  | "A_SHARP_MAJOR"
  | "B_MAJOR"
  | "C_MINOR"
  | "C_SHARP_MINOR"
  | "D_MINOR"
  | "D_SHARP_MINOR"
  | "E_MINOR"
  | "F_MINOR"
  | "F_SHARP_MINOR"
  | "G_MINOR"
  | "G_SHARP_MINOR"
  | "A_MINOR"
  | "A_SHARP_MINOR"
  | "B_MINOR"
  | "UNKNOWN";

export type MetadataSource =
  | "MANUAL_SEED"
  | "EXTERNAL_API"
  | "USER_CONTRIBUTION"
  | "INFERRED";

export type RecommendationCreateRequest = {
  sessionId: string;
  voiceRangeLow: number;
  voiceRangeHigh: number;
  mood?: Mood | null;
};

/**
 * 곡 응답 DTO.
 *
 * 옵셔널 필드 (BE 미구현, 이슈 #77로 추가 예정):
 *   - `lowMidi` / `highMidi`: 곡 음역 (반음 단위 MIDI note number).
 *   - `difficulty`: 가창 난이도. BE가 채워주기 전에는 fe에서 `deriveDifficulty()`로 계산한다.
 *
 * 옵셔널 처리 이유: 백엔드 PR(#77)이 머지되기 전에 fe(#76)가 먼저 카드 UI를 다듬는다.
 * BE가 필드를 추가하면 SongCard가 응답값을 우선 사용하고, 없으면 client-side fallback한다.
 */
export type SongResponse = {
  id: number;
  title: string;
  artist: string;
  releaseYear: number | null;
  keyOriginal: MusicalKey;
  bpm: number | null;
  mood: Mood | null;
  language: string | null;
  genre: string | null;
  tjNumber: string | null;
  kyNumber: string | null;
  metadataSource: MetadataSource;
  lowMidi?: number | null;
  highMidi?: number | null;
  difficulty?: "EASY" | "NORMAL" | "HARD" | null;
};

export type RecommendedSongResponse = {
  song: SongResponse;
  score: number;
  matchReason: string;
  rankPosition: number;
};

export type RecommendationResponse = {
  requestId: number;
  recommendations: RecommendedSongResponse[];
};

export function createRecommendation(
  request: RecommendationCreateRequest,
): Promise<RecommendationResponse> {
  return apiFetch<RecommendationResponse>("/api/v1/recommendations", {
    method: "POST",
    body: request,
  });
}

export function readRecommendation(
  id: number,
): Promise<RecommendationResponse> {
  return apiFetch<RecommendationResponse>(`/api/v1/recommendations/${id}`);
}
