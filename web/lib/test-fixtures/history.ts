/**
 * /history 페이지 테스트용 fixture builder.
 *
 * 목적:
 *   - `web/app/history/page.test.tsx` + (재진입 예정) `page.race.test.tsx` 에서
 *     중복 정의되던 inline entry/response 빌더를 한 곳에 모아 DRY 보존.
 *   - 테스트가 "이 케이스에서 의미 있는 필드" 만 명시하면 나머지는 sane default 로
 *     채워 가독성 ↑ + 도메인 필드 추가 시 fixture 한 곳만 갱신.
 *
 * 도메인 타입 (page 컴포넌트의 type 정의 그대로 따름, 변경 금지):
 *   - LocalHistoryEntry  = store `RecommendationHistoryEntry`         (localStorage source)
 *   - BackendHistoryEntry = API   `RecommendationHistoryEntryResponse` (BE source-of-truth)
 *   - VoiceRangeSnapshot  = API   `VoiceRangeSnapshotResponse`         (voice-range time-series)
 *
 * 디자인 원칙:
 *   - 모든 builder 가 `Partial<...>` overrides 받아 한 줄 분기 가능.
 *   - 기본값은 a11y/렌더링/voice-range delta 카드 분기 등 "의미를 안 흐리는" 값.
 *   - `requestId` 는 UUIDv7 문자열 (issue #422) — 정수 시드를 받아 결정적 UUID 로
 *     변환하는 `ridFromSeed(seed)` 헬퍼를 함께 export.
 *
 * 도입 사유 (2026-05-24, PR #1061 race-helpers + PR #1069 history race guard 후속):
 *   - `page.test.tsx` 안에서 `buildEntry()` 가 page.race.test.tsx 작성 시 그대로
 *     복사돼야 했고, BE response 는 7회 inline 작성 — 변경 시 sync 비용 ↑.
 *   - 사용자 architect 방향 (단순화 + code-enforce) 에 따라 fixture 1곳 통합.
 */

import type { RecommendedSongResponse } from "@/lib/api/recommendation";
import type {
  RecommendationHistoryEntryResponse,
  RecommendationHistoryListResponse,
} from "@/lib/api/recommendationHistory";
import type {
  VoiceRangeHistoryResponse,
  VoiceRangeSnapshotResponse,
} from "@/lib/api/voiceRangeHistory";
import type { RecommendationHistoryEntry } from "@/store/history";

// 도메인 type alias — page 컴포넌트 / API 모듈 정의 그대로 재export.
// fixture 호출 측이 `import type { LocalHistoryEntry } from "@/lib/test-fixtures/history"`
// 한 줄로 동일 타입을 받을 수 있게 한다 (DX). 도메인 타입 자체는 변경하지 않는다.
export type LocalHistoryEntry = RecommendationHistoryEntry;
export type BackendHistoryEntry = RecommendationHistoryEntryResponse;
export type VoiceRangeSnapshot = VoiceRangeSnapshotResponse;

/**
 * 정수 시드로 결정적 UUIDv7-스타일 문자열을 만든다.
 *
 * issue #422 후속: BE `requestId` 가 number → UUIDv7 string 으로 전환됨에 따라
 * 테스트 fixture 도 string 타입 정합성을 맞춘다. seed 를 그대로 hex 로 박아
 * "어느 entry 인지" 의미를 보존한다 (예: seed=1 → ...000000000001).
 */
export function ridFromSeed(seed: number): string {
  return `01933b1c-7f8a-7c2d-9b3e-${seed.toString(16).padStart(12, "0")}`;
}

/**
 * "지금부터 N분 전" 의 ISO8601 문자열을 반환 — 테스트의 상대 시간 라벨 검증에 사용.
 *
 * 별도 헬퍼로 추출한 이유:
 *   - `new Date(Date.now() - N * 60 * 1000).toISOString()` 가 거의 모든 케이스에서 반복.
 *   - default `requestedAt` 도 본 헬퍼로 통일 — fixture 호출 측이 시간을 신경 안 써도 됨.
 */
export function minutesAgoIso(minutes: number): string {
  return new Date(Date.now() - minutes * 60 * 1000).toISOString();
}

/**
 * RecommendedSongResponse 빌더 — 곡 메타 + 점수/순위/매칭 사유 한 묶음.
 *
 * 호출 측이 곡 id 만 줘도 의미 있는 곡명/가수명을 자동 생성 (`곡-${id}`).
 * BE 응답 / localStorage entry 양쪽이 같은 shape 의 songs 배열을 갖기 때문에
 * 본 빌더 한 개를 양쪽에서 재사용한다.
 */
export function buildRecommendedSong(
  overrides: Partial<RecommendedSongResponse> & { songId?: number } = {},
): RecommendedSongResponse {
  const { songId: songIdOverride, ...rest } = overrides;
  const songId = songIdOverride ?? rest.song?.id ?? 1;
  const rankPosition = rest.rankPosition ?? 1;
  return {
    rankPosition,
    score: rest.score ?? 0.9 - (rankPosition - 1) * 0.05,
    matchReason: rest.matchReason ?? "음역 매칭",
    song: {
      id: songId,
      title: `곡-${songId}`,
      artist: `가수-${songId}`,
      releaseYear: 2024,
      keyOriginal: "C_MAJOR",
      bpm: 110,
      mood: "UPBEAT",
      language: "ko",
      genre: "POP",
      tjNumber: `T-${songId}`,
      kyNumber: `K-${songId}`,
      metadataSource: "MANUAL_SEED",
      ...(rest.song ?? {}),
    },
    ...rest,
  } satisfies RecommendedSongResponse;
}

/**
 * localStorage 히스토리 entry (`RecommendationHistoryEntry`) 빌더.
 *
 * 의미 있는 override:
 *   - `id` (key)
 *   - `requestedAt` (상대 시간 라벨)
 *   - `songs` (배열을 직접 줄 수도 있고, 헬퍼 `buildLocalEntryFromSongIds` 사용 가능)
 *   - `voiceRangeLowMidi` / `voiceRangeHighMidi` (delta 카드 분기)
 */
export function buildLocalEntry(
  overrides: Partial<LocalHistoryEntry> = {},
): LocalHistoryEntry {
  const id = overrides.id ?? "e-1";
  // id 에서 정수 부분만 뽑아 seed 로 사용 (예: "e-12" → 12, "entry-abc" → 1).
  const seed = parseInt(id.replace(/\D/g, ""), 10) || 1;
  return {
    id,
    requestedAt: overrides.requestedAt ?? minutesAgoIso(10),
    requestId: overrides.requestId ?? ridFromSeed(seed),
    voiceRangeId: overrides.voiceRangeId ?? 42,
    excludedSongIds: overrides.excludedSongIds ?? [],
    songs:
      overrides.songs ??
      [buildRecommendedSong({ songId: seed })],
    ...overrides,
  };
}

/**
 * 곡 id 배열로 localStorage entry 를 빌드 — 기존 `buildEntry(id, requestedAt, songIds, overrides)`
 * 시그니처와 동등한 ergonomic helper.
 *
 * 사용처 예시:
 *   buildLocalEntryFromSongIds("e-1", minutesAgoIso(10), [10, 20, 30, 40, 50])
 */
export function buildLocalEntryFromSongIds(
  id: string,
  requestedAt: string,
  songIds: number[],
  overrides: Partial<LocalHistoryEntry> = {},
): LocalHistoryEntry {
  return buildLocalEntry({
    id,
    requestedAt,
    songs: songIds.map((songId, idx) =>
      buildRecommendedSong({ songId, rankPosition: idx + 1 }),
    ),
    ...overrides,
  });
}

/**
 * BE 응답의 entry 1건 (`RecommendationHistoryEntryResponse`) 빌더.
 *
 * 의미 있는 override:
 *   - `sessionId` (BE 응답 source 분기)
 *   - `recommendations` (곡 메타 배열)
 *   - `mood` / `preferredBpm` (부제목 메타 분기)
 *   - `requestedAt` (시간 라벨)
 */
export function buildBackendEntry(
  overrides: Partial<BackendHistoryEntry> = {},
): BackendHistoryEntry {
  const seed = overrides.requestId
    ? // 사용자가 requestId 를 직접 줬다면 seed 추출 안 함 (그대로 사용).
      0
    : 1;
  return {
    requestId: overrides.requestId ?? ridFromSeed(seed || 1),
    sessionId: overrides.sessionId ?? "sess-be",
    voiceRangeLow: overrides.voiceRangeLow ?? 52,
    voiceRangeHigh: overrides.voiceRangeHigh ?? 70,
    mood: overrides.mood ?? null,
    preferredBpm: overrides.preferredBpm ?? null,
    requestedAt: overrides.requestedAt ?? minutesAgoIso(5),
    recommendations:
      overrides.recommendations ??
      [buildRecommendedSong({ songId: 100 })],
    ...overrides,
  };
}

/**
 * BE list response 래퍼 — `readRecommendationHistory` mock resolved value 직접 빌드.
 *
 * 사용처 예시:
 *   readRecommendationHistoryMock.mockResolvedValueOnce(
 *     buildBackendListResponse([buildBackendEntry({ ... })])
 *   );
 */
export function buildBackendListResponse(
  entries: BackendHistoryEntry[] = [],
): RecommendationHistoryListResponse {
  return { recommendationHistoryResponses: entries };
}

/**
 * 음역 측정 snapshot (`VoiceRangeSnapshotResponse`) 빌더.
 *
 * 의미 있는 override:
 *   - `lowMidi` / `highMidi` (delta 계산)
 *   - `sourceMethod` (UI 메타 라벨)
 *   - `measuredAt` (시계열 정렬)
 */
export function buildVoiceRangeSnapshot(
  overrides: Partial<VoiceRangeSnapshot> = {},
): VoiceRangeSnapshot {
  return {
    id: overrides.id ?? 1,
    lowMidi: overrides.lowMidi ?? 52,
    highMidi: overrides.highMidi ?? 70,
    lowestNoteName: overrides.lowestNoteName ?? "E3",
    highestNoteName: overrides.highestNoteName ?? "A4",
    sourceMethod: overrides.sourceMethod ?? "SELF_REPORT",
    measuredAt: overrides.measuredAt ?? "2026-05-21T08:00:00",
    ...overrides,
  };
}

/**
 * voice-range-history list response 래퍼 — `readVoiceRangeHistory` mock resolved value.
 */
export function buildVoiceRangeListResponse(
  snapshots: VoiceRangeSnapshot[] = [],
): VoiceRangeHistoryResponse {
  return { voiceRangeSnapshotResponses: snapshots };
}
