package com.mobruji.song.api.dto;

import java.util.List;

import com.mobruji.song.domain.Song;

/**
 * 자동완성 제안(SearchSuggestion) 응답 (spec {@code song-search-and-filter.md} §5-2). 경량 — 곡 카드
 * 전체가 아니라 {@code id}/{@code title}/{@code artist} 만 담아 debounce 호출 비용을 낮춘다.
 *
 * @param items 제안 항목 (relevance prefix 정렬, 상위 N).
 */
public record SearchSuggestResponse(List<Item> items) {

    /**
     * 제안 항목 1건.
     *
     * @param id     곡 ID.
     * @param title  제목.
     * @param artist 가수.
     */
    public record Item(Long id, String title, String artist) {

        public static Item from(final Song song) {
            return new Item(song.getId(), song.getTitle(), song.getArtist());
        }
    }

    public static SearchSuggestResponse from(final List<Song> songs) {
        return new SearchSuggestResponse(songs.stream().map(Item::from).toList());
    }
}
