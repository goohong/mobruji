/**
 * 곡 검색/단건 조회 API 클라이언트.
 *
 * BE 컨트롤러 `com.mobruji.song.SongController`와 1:1 매칭:
 *   GET /api/v1/songs?keyword=<query>  → search (List<SongResponse>)
 *   GET /api/v1/songs/{id}             → read (단건)
 *
 * 빈 keyword 처리:
 *   - BE `SongService.searchByKeyword`는 keyword가 null이거나 trim 시 빈 문자열이면
 *     `List.of()` (빈 배열)를 반환한다. 검색 페이지에서는 입력이 비었을 때 호출을
 *     생략하거나, 호출 결과가 빈 배열임을 그대로 노출한다.
 *
 * 단건 조회(`readSongById`):
 *   - 곡 상세 페이지(`/songs/[id]`)에서 사용한다 (이슈 #100 / PR #101).
 *   - BE는 미존재 id에 대해 404를 던지며, 클라이언트는 `ApiError.status === 404`로
 *     "곡 없음" 분기를 처리한다.
 *
 * SongResponse 타입은 추천 모듈(recommendation.ts)이 이미 BE DTO와 1:1로 정의해
 * 둔 것을 재사용한다. lowMidi/highMidi/difficulty는 옵셔널(BE 미구현 필드).
 */

import { apiFetch } from "./client";
import type { SongResponse } from "./recommendation";

export type { SongResponse } from "./recommendation";

/**
 * 곡 검색.
 *
 * @param keyword 제목/아티스트 부분 일치. 비어 있거나 undefined면 빈 배열 반환(서버 약속).
 * @param signal AbortController 신호. 입력 디바운스/재호출 시 이전 요청 취소에 사용.
 */
export function searchSongs(
  keyword?: string,
  signal?: AbortSignal,
): Promise<SongResponse[]> {
  const query =
    keyword !== undefined && keyword.trim().length > 0
      ? `?keyword=${encodeURIComponent(keyword.trim())}`
      : "";
  return apiFetch<SongResponse[]>(`/api/v1/songs${query}`, { signal });
}

/**
 * 곡 단건 조회.
 *
 * @param id 곡 ID (양수). 음수/0은 그대로 BE에 전달되며 BE는 404로 응답한다.
 * @param signal AbortController 신호. React Query가 unmount 시 취소 가능.
 * @throws {ApiError} 미존재 id면 404로 떨어진다 (호출 측에서 상태코드로 분기).
 */
export function readSongById(
  id: number,
  signal?: AbortSignal,
): Promise<SongResponse> {
  return apiFetch<SongResponse>(`/api/v1/songs/${id}`, { signal });
}
