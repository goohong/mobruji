package com.mobruji.song.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import com.mobruji.song.domain.Difficulty;
import com.mobruji.song.domain.Mood;

class SongSearchCriteriaTest {

    private static SongSearchCriteria of(
            final String keyword, final Integer fitLow, final Integer fitHigh,
            final String sort, final int page, final int size) {
        return SongSearchCriteria.of(keyword, null, null, null, fitLow, fitHigh, sort, page, size);
    }

    @Test
    @DisplayName("sort=relevance 인데 keyword 없으면 title 로 fallback (Q4-a)")
    void relevance_withoutKeyword_fallsBackToTitle() {
        final SongSearchCriteria criteria = of(null, null, null, "relevance", 0, 20);
        assertThat(criteria.sort()).isEqualTo(SongSearchSort.TITLE);
    }

    @Test
    @DisplayName("keyword 있으면 default sort=relevance")
    void withKeyword_defaultsToRelevance() {
        final SongSearchCriteria criteria = of("발라드", null, null, null, 0, 20);
        assertThat(criteria.sort()).isEqualTo(SongSearchSort.RELEVANCE);
        assertThat(criteria.hasKeyword()).isTrue();
    }

    @Test
    @DisplayName("keyword 명시+공백이면 isExplicitlyEmptyKeyword true")
    void explicitlyEmptyKeyword() {
        assertThat(of("   ", null, null, null, 0, 20).isExplicitlyEmptyKeyword()).isTrue();
        assertThat(of(null, null, null, null, 0, 20).isExplicitlyEmptyKeyword()).isFalse();
    }

    @Test
    @DisplayName("fit 한쪽만 주면 400 (both-or-neither)")
    void fit_onlyOne_throws() {
        assertThatThrownBy(() -> of(null, 48, null, null, 0, 20))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> of(null, null, 64, null, 0, 20))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("fitHigh < fitLow 면 400")
    void fit_highBelowLow_throws() {
        assertThatThrownBy(() -> of(null, 64, 48, null, 0, 20))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("MIDI 범위 밖 fit 은 400")
    void fit_outOfMidiRange_throws() {
        assertThatThrownBy(() -> of(null, 5, 64, null, 0, 20))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("size > 100 이면 400 (DoS 가드)")
    void size_tooLarge_throws() {
        assertThatThrownBy(() -> of(null, null, null, null, 0, 101))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("미정의 difficulty/mood 값은 400")
    void invalidEnumCsv_throws() {
        assertThatThrownBy(() -> SongSearchCriteria.of(
                null, null, "SUPERHARD", null, null, null, null, 0, 20))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> SongSearchCriteria.of(
                null, null, null, "HAPPY", null, null, null, 0, 20))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("유효 CSV 는 enum 리스트로 정규화")
    void validEnumCsv_parses() {
        final SongSearchCriteria criteria = SongSearchCriteria.of(
                null, "발라드", "EASY,NORMAL", "CALM", null, null, null, 0, 20);
        assertThat(criteria.difficulties()).containsExactly(Difficulty.EASY, Difficulty.NORMAL);
        assertThat(criteria.moods()).containsExactly(Mood.CALM);
        assertThat(criteria.genres()).containsExactly("발라드");
    }
}
