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

/**
 * 추천 성별 필터(선택 입력).
 *
 * BE `RecommendationCreateRequest.gender`(#1767/#1781 genderFit) 와 1:1 매칭. 고른 성별의
 * 곡(`Song.vocalGender`)에 가중을 주는 신호로, 배타 제외가 아니라 가산이다. 미입력(null/생략)이면
 * genderFit=0 으로 처리돼 랭킹에 영향이 없다(하위호환). BE 곡측 enum 은 `MIXED` 도 갖지만 요청
 * 입력은 의미상 `MALE`/`FEMALE` 둘뿐이다.
 */
export type RequestedGender = "MALE" | "FEMALE";

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
 * 보컬 성별.
 *
 * BE `com.mobruji.song.domain.VocalGender` 와 1:1 매칭. P-D 시퀀스 요청의 선택 성별 필터
 * 입력으로 쓰이며, 미입력(null/생략)이면 성별 신호 기여 0 으로 처리된다(하위호환).
 */
export type VocalGender = "MALE" | "FEMALE" | "MIXED";

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
 * 1차로 web 은 P-D 시퀀스(#1601)·P-E 안전곡(#1600) 모드를 노출한다.
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
   * 추천 성별 필터(선택). BE #1767/#1781 genderFit 신호 입력(MALE/FEMALE).
   * null/생략 시 성별 가중 없음(하위호환). 결정성 seed 입력에도 포함된다.
   */
  gender?: RequestedGender | null;
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
 * 조옮김 권장 필드 (BE #1544, P-A 페르소나):
 *   - `suggestedTranspose`: voiceFit 이 낮은 곡에 권장하는 조옮김량(반음 정수, 예: `-6`).
 *     원조(原調)가 음역에 잘 맞아 조옮김이 불요한 곡이나 산정 근거가 없는 경우 `null`.
 *   - `transposedVoiceFit` (0~1): 권장 조옮김을 적용한 뒤 재계산한 음역 적합도. `voiceFit`과
 *     동일 산식이라 직접 비교 가능(예: 0.3 → 0.8). 조옮김 권장이 없으면 `null`.
 *   - `suggestedTransposeReason`: 조옮김 권장 사유 한국어 문장(예: "6키 내려 부르면 음역대에
 *     더 잘 맞아요"). 권장이 없으면 `null`.
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
  suggestedTranspose?: number | null;
  transposedVoiceFit?: number | null;
  suggestedTransposeReason?: string | null;
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
 * P-D 모임 사회자 시퀀스 추천의 자리 단계 식별자.
 *
 * 단일 추천과 달리 P-D 는 "분위기 흐름"(워밍업 → 고조 → 마무리)을 단계별 곡 묶음으로
 * 산출한다(persona-expansion-social-emotional.md §2 P-D / §5-1). BE `SequenceStage` enum
 * (be #1837, `POST /api/v1/recommendations/sequence`)과 1:1 UPPER 매칭한다.
 *
 * - `WARMUP` 워밍업 — 다 같이 편하게 자리를 연다(분위기 `CALM`).
 * - `PEAK` 고조 — 신나는 곡으로 분위기를 끌어올린다(분위기 `UPBEAT`).
 * - `CLOSING` 마무리 — 감성적으로 흐름을 닫는다(분위기 `EMOTIONAL`).
 */
export type SequenceStage = "WARMUP" | "PEAK" | "CLOSING";

/**
 * 한 자리 단계의 추천 묶음. BE `SequenceRecommendationResponse.StageResponse`(be #1837)와
 * 1:1 매칭한다.
 *
 * 단계별로 다른 분위기(`mood`) 입력으로 산출되며, `recommendations` 는 단일 추천과 동일한
 * `RecommendedSongResponse` 형상이라 결과 카드 렌더를 재사용한다. `requestId` 는 단계별
 * 고유(Long)라 단계별 곡 피드백·재조회를 단일 추천과 같은 경로로 처리할 수 있다.
 * `relaxed`/`relaxedFilters` 는 풀 소진 시 BE 가 일부 필터를 완화해 단계를 채웠음을 알린다.
 */
export type SequenceStageBundle = {
  stage: SequenceStage;
  mood: Mood;
  stageReason: string;
  requestId: number;
  relaxed: boolean;
  relaxedFilters: string[];
  recommendations: RecommendedSongResponse[];
};

/**
 * P-D 시퀀스 추천 요청. BE `SequenceRecommendationCreateRequest`(be #1837)와 1:1 매칭한다.
 *
 * `voiceRangeLow`/`voiceRangeHigh` 는 좌중 공통·평균 음역 힌트(필수) — 단일 추천과 같은
 * 음역 적합도 산식을 재사용하며 중앙 편향으로 한쪽 극단 쏠림을 막는다(§4 비-과편향 가드).
 * `ageGroup`(좌중 대표 연령대)·`gender`(성별 필터)·`songsPerStage`(단계별 곡 수 상한)는
 * 모두 선택이며, 미지정 필드는 생략한다(BE 결정성 seed 입력 정합, 하위호환).
 */
export type SequenceRecommendationRequest = {
  sessionId: string;
  voiceRangeLow: number;
  voiceRangeHigh: number;
  /** 좌중 대표 연령대(선택). spec §2 P-D 입력 신호 — generationFit 대표값. */
  ageGroup?: AgeGroup | null;
  /** 좌중 성별 필터(선택). 미입력 시 성별 신호 기여 0(하위호환). */
  gender?: VocalGender | null;
  /** 단계별 노출 곡 수 상한(선택, 1~50). 미입력 시 단일 추천 기본 개수. */
  songsPerStage?: number | null;
};

/**
 * P-D 시퀀스 추천 응답. BE `SequenceRecommendationResponse`(be #1837)와 1:1 매칭한다.
 *
 * 단일 추천 envelope 와 달리 단계별 곡 묶음(`stages`)을 자리 흐름(워밍업 → 고조 → 마무리)
 * 순서대로 돌려준다. `persona` 는 이 시퀀스를 산출한 페르소나 식별자(`P-D`). 단일 추천과 달리
 * top-level `requestId` 는 없고 각 단계가 고유 `requestId` 를 갖는다.
 */
export type SequenceRecommendationResponse = {
  persona: RecommendationPersona;
  stages: SequenceStageBundle[];
};

/**
 * P-D 모임 사회자 시퀀스 추천 생성.
 *
 * 신규 엔드포인트 `POST /api/v1/recommendations/sequence`(be #1837, spec §8 Q1 선택지 b —
 * 응답 형상이 단일 추천과 달라 별도 엔드포인트). 워밍업 → 고조 → 마무리 3단계 묶음을
 * 단계별 다른 분위기 입력으로 산출한 응답을 그대로 돌려준다.
 */
export function createSequenceRecommendation(
  request: SequenceRecommendationRequest,
): Promise<SequenceRecommendationResponse> {
  return apiFetch<SequenceRecommendationResponse>(
    "/api/v1/recommendations/sequence",
    {
      method: "POST",
      body: request,
    },
  );
}

/**
 * "부른 곡 기반 다음곡 추천"(#1486) 요청.
 *
 * BE `POST /api/v1/recommendations/next`(`createFromSeeds`)와 1:1 매칭. seed 곡들의
 * 음역대·분위기·BPM 을 도출해 이어 부르기 좋은 다음 곡을 결정성 있게 추천한다. 쇼츠식
 * 스와이프 덱(#1489/#1763)의 무한 로드 토대 — 사용자가 좋아요한 곡을 seed 로, 이미 본 곡을
 * `excludeSongIds` 로 넘겨 끊김 없이 다음 batch 를 이어 붙인다.
 *
 * - `seedSongIds`: 최소 1개(필수). seed 곡 자체는 결과에서 자동 제외된다.
 * - `excludeSongIds`: seed 외 추가 제외(이미 본/패스한 곡). 미입력 시 빈 리스트로 정규화.
 * - `excludeSessionHistory` / `useSessionFeedback`: 미입력 시 BE 기본값(false / true).
 */
export type NextRecommendationRequest = {
  sessionId: string;
  seedSongIds: number[];
  excludeSongIds?: number[];
  excludeSessionHistory?: boolean;
  useSessionFeedback?: boolean;
};

/**
 * 부른 곡 기반 다음곡 추천 생성. 응답 envelope 는 단일 추천과 동일한
 * `{ requestId, recommendations }` 형상이다(`POST /api/v1/recommendations/next`).
 */
export function nextRecommendation(
  request: NextRecommendationRequest,
): Promise<RecommendationResponse> {
  return apiFetch<RecommendationResponse>("/api/v1/recommendations/next", {
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
