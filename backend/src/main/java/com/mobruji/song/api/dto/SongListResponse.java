package com.mobruji.song.api.dto;

import java.util.List;

import com.mobruji.song.application.SongSearchPage;

/**
 * 곡 검색·필터 wrapper 응답 (spec {@code song-search-and-filter.md} §5-2). 둘러보기 spec 과 공유하는
 * 페이지네이션 골격 — {@code items} = 기존 {@link SongResponse} 배열 + 페이지 메타.
 *
 * @param items      현재 페이지 곡.
 * @param page       0-base 페이지 인덱스.
 * @param size       페이지 크기.
 * @param totalCount 필터 통과 전체 곡 수.
 * @param hasNext    다음 페이지 존재 여부.
 */
public record SongListResponse(
        List<SongResponse> items,
        int page,
        int size,
        long totalCount,
        boolean hasNext
) {

    public static SongListResponse from(final SongSearchPage page) {
        return new SongListResponse(
                page.items().stream().map(SongResponse::from).toList(),
                page.page(),
                page.size(),
                page.totalCount(),
                page.hasNext());
    }
}
