package com.mobruji.recommendation.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.recommendation.domain.ScoreBreakdown;

class ScoreBreakdownResponseTest {

    @Test
    @DisplayName("from: domain ScoreBreakdown의 5신호를 그대로 DTO로 매핑한다")
    void from_mapsAllFields() {
        final ScoreBreakdown breakdown = new ScoreBreakdown(1.0, 0.8, 0.0, 1.0, 1.0);

        final ScoreBreakdownResponse response = ScoreBreakdownResponse.from(breakdown);

        assertThat(response.keyMatch()).isEqualTo(1.0);
        assertThat(response.rangeFit()).isEqualTo(0.8);
        assertThat(response.genreMatch()).isEqualTo(0.0);
        assertThat(response.moodMatch()).isEqualTo(1.0);
        assertThat(response.popularity()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("from: null 입력(과거 추천 재조회 경로)은 null 반환 — 응답에 breakdown 필드를 그대로 null로 노출")
    void from_null_returnsNull() {
        assertThat(ScoreBreakdownResponse.from(null)).isNull();
    }
}
