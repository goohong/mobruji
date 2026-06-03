package com.mobruji.song.application;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 곡 검색 정렬 기준 (spec {@code song-search-and-filter.md} §5-2).
 *
 * <ul>
 * <li>{@link #RELEVANCE} — 검색 적합도(SearchRelevance) tier. {@code keyword} 가 있어야 의미 있음 —
 * 없으면 호출 측에서 {@link #TITLE} 로 fallback (Q4-a).</li>
 * <li>{@link #TITLE} — 제목 가나다순.</li>
 * <li>{@link #RELEASE_YEAR} — 발매 연도순.</li>
 * </ul>
 */
public enum SongSearchSort {

    RELEVANCE,
    TITLE,
    RELEASE_YEAR;

    /**
     * 쿼리 파라미터 문자열을 enum 으로 파싱한다. null/blank 면 {@code fallback} 을 반환하고, 정의되지
     * 않은 값이면 400.
     */
    public static SongSearchSort parse(final String raw, final SongSearchSort fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return SongSearchSort.valueOf(raw.trim().toUpperCase());
        } catch (final IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "sort must be one of relevance, title, releaseYear: " + raw);
        }
    }
}
