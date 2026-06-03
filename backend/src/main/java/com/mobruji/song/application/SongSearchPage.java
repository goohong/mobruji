package com.mobruji.song.application;

import java.util.List;

import com.mobruji.song.domain.Song;

/**
 * 곡 검색 결과 한 페이지 (spec {@code song-search-and-filter.md} §5-2). API 의 {@code SongListResponse}
 * wrapper 로 매핑된다.
 *
 * @param items      현재 페이지의 곡 (정렬 적용됨).
 * @param page       0-base 페이지 인덱스.
 * @param size       요청 페이지 크기.
 * @param totalCount 필터 통과 전체 곡 수.
 * @param hasNext    다음 페이지 존재 여부.
 */
public record SongSearchPage(
        List<Song> items,
        int page,
        int size,
        long totalCount,
        boolean hasNext
) {
}
