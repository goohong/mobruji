package com.mobruji.recommendation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ScoreBreakdownTest {

    @Test
    @DisplayName("정상: 6신호 모두 [0,1] 안이면 생성 가능")
    void create_valid() {
        final ScoreBreakdown breakdown = new ScoreBreakdown(1.0, 0.5, 0.0, 1.0, 1.0, 0.75);

        assertThat(breakdown.keyMatch()).isEqualTo(1.0);
        assertThat(breakdown.rangeFit()).isEqualTo(0.5);
        assertThat(breakdown.genreMatch()).isEqualTo(0.0);
        assertThat(breakdown.moodMatch()).isEqualTo(1.0);
        assertThat(breakdown.popularity()).isEqualTo(1.0);
        assertThat(breakdown.tempoMatch()).isEqualTo(0.75);
    }

    @Test
    @DisplayName("경계: 0.0과 1.0은 허용된다")
    void create_boundary_zero_and_one() {
        new ScoreBreakdown(0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        new ScoreBreakdown(1.0, 1.0, 1.0, 1.0, 1.0, 1.0);
        // 예외 없으면 통과
    }

    @Test
    @DisplayName("음수: 어느 신호든 [0,1]을 벗어나면 IllegalArgumentException")
    void create_negative_throws() {
        assertThatThrownBy(() -> new ScoreBreakdown(-0.1, 0.5, 0.0, 0.0, 1.0, 0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyMatch");
        assertThatThrownBy(() -> new ScoreBreakdown(0.5, -0.1, 0.0, 0.0, 1.0, 0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rangeFit");
        assertThatThrownBy(() -> new ScoreBreakdown(0.5, 0.5, -0.1, 0.0, 1.0, 0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("genreMatch");
        assertThatThrownBy(() -> new ScoreBreakdown(0.5, 0.5, 0.0, -0.1, 1.0, 0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("moodMatch");
        assertThatThrownBy(() -> new ScoreBreakdown(0.5, 0.5, 0.0, 0.0, -0.1, 0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("popularity");
        assertThatThrownBy(() -> new ScoreBreakdown(0.5, 0.5, 0.0, 0.0, 1.0, -0.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tempoMatch");
    }

    @Test
    @DisplayName("1.0 초과: IllegalArgumentException")
    void create_aboveOne_throws() {
        assertThatThrownBy(() -> new ScoreBreakdown(1.1, 0.5, 0.0, 0.0, 1.0, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScoreBreakdown(0.5, 0.5, 0.0, 0.0, 1.0, 1.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tempoMatch");
    }

    @Test
    @DisplayName("NaN: IllegalArgumentException")
    void create_nan_throws() {
        assertThatThrownBy(() -> new ScoreBreakdown(Double.NaN, 0.5, 0.0, 0.0, 1.0, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScoreBreakdown(0.5, 0.5, 0.0, 0.0, 1.0, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tempoMatch");
    }
}
