/**
 * 곡 검색 API 클라이언트.
 *
 * BE 컨트롤러 `com.mobruji.song.SongController`와 1:1 매칭:
 *   GET /api/v1/songs?keyword=<query>  → search (List<SongResponse>)
 *   GET /api/v1/songs/{id}             → read (단건, 본 클라이언트 미노출)
 *
 * 빈 keyword 처리:
 *   - BE `SongService.searchByKeyword`는 keyword가 null이거나 trim 시 빈 문자열이면
 *     `List.of()` (빈 배열)를 반환한다. 검색 페이지에서는 입력이 비었을 때 호출을
 *     생략하거나, 호출 결과가 빈 배열임을 그대로 노출한다.
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
