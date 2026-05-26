/**
 * `/songs` 검색 페이지 테스트용 fixture builder.
 *
 * 목적:
 *   - `web/app/songs/page.test.tsx` (#91 #92 #93 #107 #118 #323 #470) +
 *     `web/app/songs/page.race.test.tsx` (#1100, 본 PR 후속) 에서 사용하는
 *     `SongResponse` list shape 한 곳에 통합.
 *   - 호출 측이 "이 케이스에서 의미 있는 필드" 만 명시하면 나머지는 sane default —
 *     도메인 필드 추가 시 본 fixture 한 곳만 갱신.
 *
 * 도메인 타입:
 *   - `SongResponse` = `@/lib/api/song` 재export (= recommendation.ts 의 원본 타입).
 *     본 fixture 는 타입 자체를 다시 정의하지 않고 그대로 사용한다.
 *
 * `song-detail.ts` 와 분리한 이유:
 *   - detail fixture 는 단건 + 좋아요 토글 분기 (lowMidi/highMidi default 미지정) 에
 *     초점이 있고, 본 fixture 는 검색 결과 list 식별 + race test 의 keyword 별 응답
 *     셋업 (id/title prefix 가 keyword 와 매핑) 에 초점이 있어 default 가 다르다.
 *   - 두 페이지 모두 `SongResponse` 단일 타입을 쓰지만 race 시나리오 가 다르다 (검색
 *     은 keyword race + abort + cross-contamination, 단건 은 songId 전환 + 좋아요 토글).
 *
 * 디자인 원칙:
 *   - `Partial<SongResponse>` overrides 받아 한 줄 분기 가능.
 *   - `buildSongList(keyword, count)` — keyword 기반 list 빌더. 곡명/아티스트가 keyword
 *     를 포함해 race test 가 응답 ↔ keyword 매핑을 카드 text 로 검증 가능.
 *   - inline 곡명은 "{keyword}-{idx}" 로 keyword 별 응답 식별 쉬움.
 *
 * 도입 사유 (2026-05-26):
 *   - `page.test.tsx` 의 inline `twoSongsResponse` + 4건 inline `SongResponse` 셋업이
 *     race test 작성 시 그대로 복사돼야 했고, keyword race 시나리오 는 keyword 별
 *     서로 다른 list 응답을 요구해 inline 작성 비용 ↑.
 *   - PR #1100 (`song-detail.ts`) 패턴과 일관성 — 페이지 별 fixture 1곳 통합.
 */

import type { SongResponse } from "@/lib/api/song";

export type { SongResponse } from "@/lib/api/song";

/**
 * 단일 `SongResponse` 빌더 (검색 결과 list 의 한 entry).
 *
 * default:
 *   - id=1, title="검색곡-1", artist="아티스트-1"
 *   - keyOriginal=C_MAJOR, mood=null, genre="POP", language="ko"
 *   - lowMidi/highMidi 미지정 — race test 가 음역 정보 노출 분기 의식 안 해도 됨.
 *   - difficulty 미지정 — 호출 측이 명시한 경우만 노출.
 */
export function buildSearchSong(
  overrides: Partial<SongResponse> = {},
): SongResponse {
  const id = overrides.id ?? 1;
  return {
    id,
    title: overrides.title ?? `검색곡-${id}`,
    artist: overrides.artist ?? `아티스트-${id}`,
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

/**
 * keyword 기반 검색 결과 list 빌더.
 *
 * race test 에서 keyword 별로 서로 다른 응답을 셋업하기 쉬운 형태:
 *   - 곡명/아티스트가 keyword 를 포함 → 카드 text 로 응답 ↔ keyword 매핑 검증.
 *   - id 는 `idBase + idx` (idBase default 100) — 다른 keyword 응답 list 끼리 id 충돌
 *     없도록 호출 측이 idBase 분리 가능.
 *
 * 사용 패턴:
 *   ```ts
 *   // 첫 keyword 응답.
 *   searchSongsMock.mockReturnValueOnce(Promise.resolve(buildSongList("hello")));
 *   // 두 번째 keyword 응답 (id 충돌 회피용 idBase=200).
 *   searchSongsMock.mockReturnValueOnce(
 *     Promise.resolve(buildSongList("world", 2, 200)),
 *   );
 *   ```
 */
export function buildSongList(
  keyword: string,
  count: number = 2,
  idBase: number = 100,
): SongResponse[] {
  const list: SongResponse[] = [];
  for (let idx = 1; idx <= count; idx += 1) {
    const id = idBase + idx;
    list.push(
      buildSearchSong({
        id,
        title: `${keyword}-${idx}`,
        artist: `아티스트-${keyword}-${idx}`,
      }),
    );
  }
  return list;
}
