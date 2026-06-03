package com.mobruji.recommendation.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.song.domain.Mood;

/**
 * {@link TrendingQuery} 검증 규칙 가드 (#1488). 음역대 both-or-neither / 범위 / 양수 제약.
 */
class TrendingQueryTest {

    @Test
    @DisplayName("정상 입력 — 필터 전부 / 필터 없음 둘 다 허용")
    void validInputs() {
        assertThatCode(() -> new TrendingQuery(7, null, null, null, 10)).doesNotThrowAnyException();
        assertThatCode(() -> new TrendingQuery(30, Mood.UPBEAT, 50, 70, 5)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("periodDays < 1 → 예외")
    void rejectsNonPositivePeriod() {
        assertThatThrownBy(() -> new TrendingQuery(0, null, null, null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("periodDays");
    }

    @Test
    @DisplayName("limit < 1 → 예외")
    void rejectsNonPositiveLimit() {
        assertThatThrownBy(() -> new TrendingQuery(7, null, null, null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
    }

    @Test
    @DisplayName("voiceRange 한쪽만 입력 → 예외")
    void rejectsPartialRange() {
        assertThatThrownBy(() -> new TrendingQuery(7, null, 50, null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("both present or both absent");
        assertThatThrownBy(() -> new TrendingQuery(7, null, null, 70, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("voiceRangeLow > voiceRangeHigh → 예외")
    void rejectsInvertedRange() {
        assertThatThrownBy(() -> new TrendingQuery(7, null, 80, 50, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be <=");
    }
}
