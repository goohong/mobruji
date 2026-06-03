package com.mobruji.song.domain;

import java.util.Comparator;
import java.util.Locale;

/**
 * 검색 적합도(SearchRelevance) — 검색 결과 정렬 신호 (spec {@code song-search-and-filter.md} §5-1).
 * 추천 score 와 무관한 조회 전용 tier:
 *
 * <ol start="0">
 * <li>제목 정확 일치</li>
 * <li>제목 prefix 일치</li>
 * <li>제목 부분 일치</li>
 * <li>가수 일치</li>
 * <li>비매칭 (필터된 결과에는 없어야 함)</li>
 * </ol>
 *
 * <p>동 tier tie-break 은 제목 가나다순 — 동점 곡 순서를 안정화해 페이지네이션 중복/누락을 막는다.
 * 초성 검색(chosung)은 파생열 prefix 기준 tier 를 사용한다 (부분 매칭은 v0.2 제외).
 */
public final class SearchRelevance {

    public static final int TIER_TITLE_EXACT = 0;
    public static final int TIER_TITLE_PREFIX = 1;
    public static final int TIER_TITLE_CONTAINS = 2;
    public static final int TIER_ARTIST = 3;
    public static final int TIER_NONE = 4;

    private SearchRelevance() {
        // utility class
    }

    /**
     * {@code keyword} 에 대한 곡의 적합도 tier 를 계산한다. {@code chosung} 이면 초성 파생열 prefix
     * 기준, 아니면 완성형 부분 일치 기준.
     */
    public static int tier(final Song song, final String keyword, final boolean chosung) {
        if (chosung) {
            return chosungTier(song, keyword);
        }
        final String normalized = keyword.toLowerCase(Locale.ROOT);
        final String title = song.getTitle().toLowerCase(Locale.ROOT);
        final String artist = song.getArtist().toLowerCase(Locale.ROOT);
        if (title.equals(normalized)) {
            return TIER_TITLE_EXACT;
        }
        if (title.startsWith(normalized)) {
            return TIER_TITLE_PREFIX;
        }
        if (title.contains(normalized)) {
            return TIER_TITLE_CONTAINS;
        }
        if (artist.contains(normalized)) {
            return TIER_ARTIST;
        }
        return TIER_NONE;
    }

    private static int chosungTier(final Song song, final String keyword) {
        final String titleChosung = song.getTitleChosung();
        final String artistChosung = song.getArtistChosung();
        if (titleChosung != null && titleChosung.equals(keyword)) {
            return TIER_TITLE_EXACT;
        }
        if (titleChosung != null && titleChosung.startsWith(keyword)) {
            return TIER_TITLE_PREFIX;
        }
        if (artistChosung != null && artistChosung.startsWith(keyword)) {
            return TIER_ARTIST;
        }
        return TIER_NONE;
    }

    /**
     * tier asc → 제목 가나다 asc 비교자. 검색·자동완성 정렬 공용.
     */
    public static Comparator<Song> comparator(final String keyword, final boolean chosung) {
        return Comparator
                .comparingInt((Song song) -> tier(song, keyword, chosung))
                .thenComparing(Song::getTitle);
    }
}
