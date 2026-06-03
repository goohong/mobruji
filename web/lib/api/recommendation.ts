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

/**
 * 추천 요청자의 연령대(선택 입력).
 *
 * BE `com.mobruji.recommendation.domain.AgeGroup` (이슈 #1487) 와 1:1 SCREAMING_SNAKE 매칭.
 * 해당 세대의 대표 시기 곡(발매연도)에 가중을 주는 generationFit 신호의 입력으로 쓰이며,
 * 미입력(null/생략)이면 generationFit=0 으로 처리돼 랭킹에 영향이 없다(하위호환).
 */
export type AgeGroup =
  | "TEENS"
  | "TWENTIES"
  | "THIRTIES"
  | "FORTIES"
  | "FIFTIES"
  | "SIXTIES_PLUS";

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

/**
 * 추천 의도 페르소나 식별자.
 *
 * BE `com.mobruji.recommendation.domain.RecommendationPersona` 와 1:1 매칭
 * (`06-domain-model.md §4-1` 등재, persona-expansion-social-emotional.md §2).
 * 영속 엔티티가 아니라 추천 의도·랭킹 가중 프리셋의 분류 라벨이다.
 *
 * - `P-A` 연습형 / `P-B` 부른곡 기반 / `P-C` 즉석 분위기·나이대 (개인·실용 축)
 * - `P-D` 모임 사회자형 / `P-E` 안전곡형 / `P-F` 과시·킬링파트형 / `P-G` 듀엣형
 *
 * 추천 요청에서 미지정(null/생략)이면 현행 default 가중으로 동작한다(하위호환).
 * 1차(#1598/#1600)는 `P-E` 안전곡 모드만 web 에서 선택 가능하다.
 */
export type RecommendationPersona =
  | "P-A"
  | "P-B"
  | "P-C"
  | "P-D"
  | "P-E"
  | "P-F"
  | "P-G";

export type RecommendationCreateRequest = {
  sessionId: string;
  voiceRangeLow: number;
  voiceRangeHigh: number;
  mood?: Mood | null;
  /**
   * 추천 요청자의 연령대(선택). BE #1487 generationFit 신호 입력.
   * null/생략 시 세대 가중 없음(하위호환). 결정성 seed 입력에도 포함된다.
   */
  ageGroup?: AgeGroup | null;
  /**
   * 재추천 시 결과에서 제외할 곡 ID 목록.
   *
   * - PR #64 / #74로 BE 지원 완료. nullable / JSON 생략 가능.
   * - 같은 voiceRange + 다른 excludeSongIds → SeedDeriver가 다른 seed를 만들어
   *   결정성을 유지하면서 다른 결과를 보장한다 (spec §9 2026-05-21 결정 로그).
   * - 호출 측은 store(`useSessionStore.excludedSongIds`)의 누적 리스트를
   *   그대로 전달한다.
   */
  excludeSongIds?: number[];
  /**
   * 추천 의도 페르소나(선택). BE #1598(P-E 안전곡 가중 프리셋)이 받는 필드.
   *
   * - `P-E` 지정 → 안전곡 가중 프리셋(`difficulty=EASY` + `rangeFit` 여유 +
   *   느린 `tempoMatch` + `popularity` 강편향) 적용.
   * - null/생략 시 현행 default 가중(하위호환). 결정성 seed 입력에도 포함된다.
   */
  persona?: RecommendationPersona | null;
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
 *
 * 이슈 #322 (2026-05-22, BE PR #337 머지):
 *   - `albumCoverUrl`: 앨범 커버 이미지 URL. BE iTunes Search backfill 로 채워주며,
 *     backfill 미적용 곡(또는 iTunes fuzzy match 실패)은 null. fe 는 null 이거나
 *     로딩 실패 시 placeholder(음표 SVG + 그라데이션)로 fallback.
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
  albumCoverUrl?: string | null;
};

/**
 * 설명가능성 필드 (BE PR #1502 / 이슈 #1484):
 *   - `voiceFit` (0~1): 곡별 음역 적합도 점수. breakdown `rangeFit` 신호를 곡 단위로 노출.
 *   - `voiceFitReason`: 음역 적합도를 설명하는 짧은 한국어 사유 ("왜 이 곡?").
 *   - `moodFit` (0~1) / `moodFitReason`: 같은 패턴으로 분위기 적합도(#1485)를 노출.
 *
 * 연습 지원 필드 (BE #1494 / 이슈 #1550, P-A 페르소나):
 *   - `practiceDifficulty`: 곡 자체의 가창 난이도(EASY/NORMAL/HARD). 곡 음역에서 파생하므로
 *     breakdown 없는 재조회 경로에서도 채워지며, 음역 미보유 곡은 `null`.
 *   - `practiceDifficultyReason`: 최고음 + 난이도를 풀어 주는 짧은 한국어 사유. 음역 정보가
 *     없는 곡도 "정보 없음" 사유를 돌려주므로 값이 있으면 항상 노출 가능.
 *
 * breakdown 이 없는 과거 추천 재조회 경로에서는 적합도 필드는 모두 `null`/생략 — fe 는 값이
 * 있을 때만 적합도 배지/사유를 노출하고, 없으면 종전대로 matchReason + 클라이언트 추정
 * breakdown 만 보여준다.
 */
export type RecommendedSongResponse = {
  song: SongResponse;
  score: number;
  matchReason: string;
  voiceFit?: number | null;
  voiceFitReason?: string | null;
  moodFit?: number | null;
  moodFitReason?: string | null;
  practiceDifficulty?: "EASY" | "NORMAL" | "HARD" | null;
  practiceDifficultyReason?: string | null;
  /**
   * 페르소나 설명가능성 필드 (BE #1598 / 이슈 #1600, P-E 안전곡 모드):
   *   - `persona`: 이 추천을 산출한 가중 프리셋의 페르소나 식별자. 미지정 호출(default
   *     가중)에서는 `null`/생략.
   *   - `personaReason`: 페르소나별 사유 텍스트. P-E 안전곡 모드에서는 "안심 포인트"
   *     (쉬운 이유) 한 줄로 노출한다. BE 가 채워주기 전에는 web 이 곡 난이도 기반으로
   *     client-side fallback 사유를 만들어 보여준다(lib/persona.ts).
   */
  persona?: RecommendationPersona | null;
  personaReason?: string | null;
  rankPosition: number;
};

/**
 * 추천 응답 envelope.
 *
 * issue #422 (BE PR #417 후속): BE `requestId` 가 number → UUIDv7 string 으로
 * 전환됨에 따라 fe 타입도 `string` 으로 통일. localStorage 영속, 페이지 라우팅
 * (`/recommendations/{id}`), 로깅 마스킹 (`safeLog` SENSITIVE_KEYS) 모두 string
 * 가정으로 동작한다. 단건 조회는 GET path 그대로 string 을 끼워 호출한다.
 */
export type RecommendationResponse = {
  requestId: string;
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

/**
 * 단건 추천 조회.
 *
 * issue #422: id 는 UUIDv7 문자열. `encodeURIComponent` 로 안전 인코딩한다 —
 * UUID 자체는 URL safe 한 hex+`-` 조합이지만 향후 BE id 형식이 바뀔 가능성을
 * 대비한 방어 인코딩이다 (recommendationHistory.ts sessionId 와 동일 패턴).
 */
export function readRecommendation(
  id: string,
): Promise<RecommendationResponse> {
  return apiFetch<RecommendationResponse>(
    `/api/v1/recommendations/${encodeURIComponent(id)}`,
  );
}
