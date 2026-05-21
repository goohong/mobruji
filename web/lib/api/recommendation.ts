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
