package com.mobruji.song.application;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.mobruji.song.domain.ChosungDeriver;
import com.mobruji.song.domain.Difficulty;
import com.mobruji.song.domain.Mood;

/**
 * 곡 검색 요청의 파싱·검증된 표현 (spec {@code song-search-and-filter.md} §5-2). 컨트롤러의 raw
 * 쿼리 파라미터를 {@link #of} 에서 검증해 도메인 타입으로 정규화한다 — 위반 시 400.
 *
 * @param keyword        검색어 (trim 적용, 미명시면 null). {@link #keywordPresent} 와 함께 빈-keyword
 *                       정책을 표현한다.
 * @param keywordPresent {@code keyword} 파라미터가 명시됐는지 — 명시+공백이면 빈 결과 정책 (spec §3).
 * @param genres         장르 CSV (자유 문자열).
 * @param difficulties   난이도 CSV.
 * @param moods          분위기 CSV.
 * @param fitLow         음역 적합 하한 MIDI (both-or-neither).
 * @param fitHigh        음역 적합 상한 MIDI.
 * @param sort           정렬 기준 (relevance 는 keyword 있을 때만, 없으면 title fallback).
 * @param page           0-base 페이지 인덱스.
 * @param size           페이지 크기.
 */
public record SongSearchCriteria(
        String keyword,
        boolean keywordPresent,
        List<String> genres,
        List<Difficulty> difficulties,
        List<Mood> moods,
        Integer fitLow,
        Integer fitHigh,
        SongSearchSort sort,
        int page,
        int size
) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;
    public static final int MAX_KEYWORD_LENGTH = 50;
    public static final int MIDI_MIN = 12;
    public static final int MIDI_MAX = 119;

    /**
     * raw 쿼리 파라미터를 검증·정규화한다. 검증 위반은 {@link ResponseStatusException} 400.
     */
    public static SongSearchCriteria of(
            final String keyword,
            final String genre,
            final String difficulty,
            final String mood,
            final Integer fitLow,
            final Integer fitHigh,
            final String sort,
            final int page,
            final int size) {
        validatePaging(page, size);
        final boolean keywordPresent = keyword != null;
        final String trimmedKeyword = keyword == null ? null : keyword.trim();
        validateKeywordLength(trimmedKeyword);
        validateVoiceFit(fitLow, fitHigh);

        final List<Difficulty> difficulties = parseEnumCsv(
                difficulty, "difficulty", value -> Difficulty.valueOf(value.toUpperCase(Locale.ROOT)));
        final List<Mood> moods = parseEnumCsv(
                mood, "mood", value -> Mood.valueOf(value.toUpperCase(Locale.ROOT)));
        final List<String> genres = parseCsv(genre);

        final boolean hasKeyword = keywordPresent && !trimmedKeyword.isEmpty();
        SongSearchSort resolvedSort = SongSearchSort.parse(sort, hasKeyword
                ? SongSearchSort.RELEVANCE
                : SongSearchSort.TITLE);
        if (resolvedSort == SongSearchSort.RELEVANCE && !hasKeyword) {
            // Q4-a — relevance 는 keyword 없으면 무의미 → title fallback.
            resolvedSort = SongSearchSort.TITLE;
        }

        return new SongSearchCriteria(
                trimmedKeyword, keywordPresent, genres, difficulties, moods,
                fitLow, fitHigh, resolvedSort, page, size);
    }

    /**
     * keyword 가 명시됐고 비/공백이 아닌지 — 검색 매칭을 적용할지 판단한다.
     */
    public boolean hasKeyword() {
        return keywordPresent && keyword != null && !keyword.isEmpty();
    }

    /**
     * keyword 가 명시됐으나 비/공백인지 — 이 경우 빈 결과를 반환한다 (spec §3 기존 정책 유지).
     */
    public boolean isExplicitlyEmptyKeyword() {
        return keywordPresent && (keyword == null || keyword.isEmpty());
    }

    public boolean isChosung() {
        return hasKeyword() && ChosungDeriver.isChosungQuery(keyword);
    }

    private static void validatePaging(final int page, final int size) {
        if (page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be >= 0");
        }
        if (size < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "size must be >= 1");
        }
        if (size > MAX_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "size must be <= " + MAX_SIZE);
        }
    }

    private static void validateKeywordLength(final String trimmedKeyword) {
        if (trimmedKeyword != null && trimmedKeyword.length() > MAX_KEYWORD_LENGTH) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "keyword must be <= " + MAX_KEYWORD_LENGTH + " characters");
        }
    }

    private static void validateVoiceFit(final Integer fitLow, final Integer fitHigh) {
        if ((fitLow == null) != (fitHigh == null)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "fitLow and fitHigh must be provided together");
        }
        if (fitLow == null) {
            return;
        }
        if (fitLow < MIDI_MIN || fitLow > MIDI_MAX || fitHigh < MIDI_MIN || fitHigh > MIDI_MAX) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "fitLow/fitHigh must be within MIDI [" + MIDI_MIN + ", " + MIDI_MAX + "]");
        }
        if (fitHigh < fitLow) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "fitHigh must be >= fitLow");
        }
    }

    private static List<String> parseCsv(final String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .toList();
    }

    private static <T> List<T> parseEnumCsv(
            final String raw, final String field, final java.util.function.Function<String, T> mapper) {
        return parseCsv(raw).stream()
                .map(token -> mapEnum(token, field, mapper))
                .toList();
    }

    private static <T> T mapEnum(
            final String token, final String field, final java.util.function.Function<String, T> mapper) {
        try {
            return mapper.apply(token);
        } catch (final IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, field + ": 허용되지 않는 값입니다: " + token);
        }
    }
}
