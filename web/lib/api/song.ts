/**
 * 곡 검색/단건 조회 API 클라이언트.
 *
 * BE 컨트롤러 `com.mobruji.song.api.SongController`와 1:1 매칭:
 *   GET /api/v1/songs?keyword=<query>  → search (SongListResponse wrapper)
 *   GET /api/v1/songs/{id}             → read (단건)
 *
 * 응답 wrapper(`SongListResponse`):
 *   - BE가 #1551(검색·필터 API)부터 bare 배열 대신 페이지네이션 wrapper
 *     `{items, page, size, totalCount, hasNext}`를 반환한다. 필드명은 BE
 *     `com.mobruji.song.api.dto.SongListResponse` record와 정확히 일치시킨다.
 *   - 곡 목록은 `items`에 담긴다. 빈 keyword면 빈 `items` (서버 약속).
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
 * 곡 검색·필터 응답 wrapper.
 *
 * BE `com.mobruji.song.api.dto.SongListResponse` record와 1:1 매칭:
 *   - `items`: 현재 페이지 곡 배열.
 *   - `page`: 0-base 페이지 인덱스.
 *   - `size`: 페이지 크기.
 *   - `totalCount`: 필터 통과 전체 곡 수.
 *   - `hasNext`: 다음 페이지 존재 여부.
 */
export type SongListResponse = {
  items: SongResponse[];
  page: number;
  size: number;
  totalCount: number;
  hasNext: boolean;
};

/**
 * 곡 검색.
 *
 * @param keyword 제목/아티스트 부분 일치. 비어 있거나 undefined면 빈 items 반환(서버 약속).
 * @param signal AbortController 신호. 입력 디바운스/재호출 시 이전 요청 취소에 사용.
 */
export function searchSongs(
  keyword?: string,
  signal?: AbortSignal,
): Promise<SongListResponse> {
  const query =
    keyword !== undefined && keyword.trim().length > 0
      ? `?keyword=${encodeURIComponent(keyword.trim())}`
      : "";
  return apiFetch<SongListResponse>(`/api/v1/songs${query}`, { signal });
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
