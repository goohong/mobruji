/**
 * /songs/[id] 곡 상세 페이지 테스트용 fixture builder.
 *
 * 목적:
 *   - `web/app/songs/[id]/page.test.tsx` (#101 #480 #745) +
 *     `web/app/songs/[id]/page.race.test.tsx` (#1100, 본 PR) 에서 사용하는
 *     `SongResponse` shape 한 곳에 통합.
 *   - 호출 측이 "이 케이스에서 의미 있는 필드" 만 명시하면 나머지는 sane default —
 *     도메인 필드 추가 시 본 fixture 한 곳만 갱신.
 *
 * 도메인 타입:
 *   - `SongResponse` = `@/lib/api/song` 재export (= recommendation.ts 의 원본 타입).
 *     본 fixture 는 타입 자체를 다시 정의하지 않고 그대로 사용한다.
 *
 * 도입 사유 (2026-05-24, PR #1100 /songs/[id] race guard 후속):
 *   - `page.test.tsx` 의 inline `buildSong` 빌더가 race test 작성 시 그대로 복사돼야
 *     했고, 7회 inline 작성 → sync 비용 ↑.
 *   - PR #1090 (history fixture) 패턴과 일관성 — 페이지 별 fixture 1곳 통합.
 *
 * 디자인 원칙:
 *   - `Partial<SongResponse>` overrides 받아 한 줄 분기 가능.
 *   - default 는 한국어 라벨 검증을 의미 있게 트리거하는 값 (예: lowMidi/highMidi 가
 *     명시되면 difficulty 자동 derive — race test 에서는 보통 생략).
 *   - inline 곡명은 "곡-{id}" 로 race test 가 id 별 카드 식별 쉬움.
 */

import type { SongResponse } from "@/lib/api/song";

export type { SongResponse } from "@/lib/api/song";

/**
 * `SongResponse` 빌더.
 *
 * 의미 있는 override:
 *   - `id` (route param 매칭)
 *   - `title` / `artist` (race test 가 카드 식별)
 *   - `lowMidi` / `highMidi` (난이도 derive + 최고음/최저음 표기 분기)
 *   - `difficulty` (BE 가 직접 내려준 난이도 — derive 보다 우선)
 *   - `keyOriginal` / `genre` / `mood` (메타 칩 분기)
 *
 * default:
 *   - id=1, title="곡-1", artist="가수-1"
 *   - keyOriginal=C_MAJOR, mood=null, genre="POP", language="ko"
 *   - lowMidi/highMidi 미지정 — race test 가 음역 정보 노출 분기 의식 안 해도 됨.
 *   - difficulty 미지정 — 호출 측이 명시한 경우만 노출.
 */
export function buildSongResponse(
  overrides: Partial<SongResponse> = {},
): SongResponse {
  const id = overrides.id ?? 1;
  return {
    id,
    title: overrides.title ?? `곡-${id}`,
    artist: overrides.artist ?? `가수-${id}`,
    releaseYear: overrides.releaseYear ?? 2024,
    keyOriginal: overrides.keyOriginal ?? "C_MAJOR",
    bpm: overrides.bpm ?? 110,
    mood: overrides.mood ?? null,
    language: overrides.language ?? "ko",
    genre: overrides.genre ?? "POP",
    tjNumber: overrides.tjNumber ?? `T-${id}`,
    kyNumber: overrides.kyNumber ?? `K-${id}`,
    metadataSource: overrides.metadataSource ?? "MANUAL_SEED",
    ...overrides,
  };
}
