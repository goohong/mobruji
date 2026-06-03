package com.mobruji.song.infrastructure;

import java.util.List;
import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;

import com.mobruji.song.domain.Difficulty;
import com.mobruji.song.domain.Mood;
import com.mobruji.song.domain.Song;

/**
 * 곡 다축 동적 필터 엔진 (spec {@code song-search-and-filter.md} §5-8) — 검색 spec 과 둘러보기 spec
 * 이 공유할 골격. 각 메서드는 단일 필터 축의 {@link Specification} 을 반환하고, 호출 측이 AND 결합한다.
 *
 * <p>모든 필터는 AND, 같은 그룹의 CSV 다중 값은 {@code in()} = OR. enum/non-null 컬럼만 매칭하므로
 * null-필터 대상 row(예: null-mood, null-range)는 해당 필터 활성 시 자동 제외된다 (spec §3 정책).
 */
public final class SongSpecifications {

    private SongSpecifications() {
        // utility class
    }

    /**
     * 완성형 keyword 부분 일치 — {@code lower(title) LIKE %kw% OR lower(artist) LIKE %kw%}.
     */
    public static Specification<Song> keywordCompleted(final String keyword) {
        final String pattern = "%" + keyword.toLowerCase(Locale.ROOT) + "%";
        return (root, query, builder) -> builder.or(
                builder.like(builder.lower(root.get("title")), pattern),
                builder.like(builder.lower(root.get("artist")), pattern));
    }

    /**
     * 초성열 prefix 일치 — {@code titleChosung LIKE 'kwc%' OR artistChosung LIKE 'kwc%'}. leading
     * wildcard 가 없어 index range scan 을 탄다 (§5-7).
     */
    public static Specification<Song> keywordChosung(final String chosung) {
        final String pattern = chosung + "%";
        return (root, query, builder) -> builder.or(
                builder.like(root.get("titleChosung"), pattern),
                builder.like(root.get("artistChosung"), pattern));
    }

    /**
     * 장르 CSV — {@code lower(genre) IN (...)}. genre 는 자유 문자열 컬럼이라 대소문자 무시 비교.
     */
    public static Specification<Song> genreIn(final List<String> genres) {
        final List<String> lowered = genres.stream()
                .map(genre -> genre.toLowerCase(Locale.ROOT))
                .toList();
        return (root, query, builder) -> builder.lower(root.get("genre")).in(lowered);
    }

    public static Specification<Song> difficultyIn(final List<Difficulty> difficulties) {
        return (root, query, builder) -> root.get("difficulty").in(difficulties);
    }

    public static Specification<Song> moodIn(final List<Mood> moods) {
        return (root, query, builder) -> root.get("mood").in(moods);
    }

    /**
     * 음역 적합 — 곡이 {@code lowMidi >= fitLow AND highMidi <= fitHigh} ("부를 수 있는 곡", spec
     * Q3-a). null-range 곡은 null 비교가 unknown 이라 자동 제외.
     */
    public static Specification<Song> voiceFit(final int fitLow, final int fitHigh) {
        return (root, query, builder) -> builder.and(
                builder.greaterThanOrEqualTo(root.get("lowMidi"), fitLow),
                builder.lessThanOrEqualTo(root.get("highMidi"), fitHigh));
    }
}
