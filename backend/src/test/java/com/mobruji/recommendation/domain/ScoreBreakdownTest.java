package com.mobruji.recommendation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ScoreBreakdownTest {

    @Test
    @DisplayName("정상: 6신호 모두 [0,1] 안이면 생성 가능")
    void create_valid() {
        final ScoreBreakdown breakdown = new ScoreBreakdown(1.0, 0.5, 0.0, 1.0, 1.0, 0.75, 0.0, 0.0);

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
        new ScoreBreakdown(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        new ScoreBreakdown(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 0.0, 0.0);
        // 예외 없으면 통과
    }

    @Test
    @DisplayName("음수: 어느 신호든 [0,1]을 벗어나면 IllegalArgumentException")
    void create_negative_throws() {
        assertThatThrownBy(() -> new ScoreBreakdown(-0.1, 0.5, 0.0, 0.0, 1.0, 0.5, 0.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyMatch");
        assertThatThrownBy(() -> new ScoreBreakdown(0.5, -0.1, 0.0, 0.0, 1.0, 0.5, 0.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rangeFit");
        assertThatThrownBy(() -> new ScoreBreakdown(0.5, 0.5, -0.1, 0.0, 1.0, 0.5, 0.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("genreMatch");
        assertThatThrownBy(() -> new ScoreBreakdown(0.5, 0.5, 0.0, -0.1, 1.0, 0.5, 0.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("moodMatch");
        assertThatThrownBy(() -> new ScoreBreakdown(0.5, 0.5, 0.0, 0.0, -0.1, 0.5, 0.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("popularity");
        assertThatThrownBy(() -> new ScoreBreakdown(0.5, 0.5, 0.0, 0.0, 1.0, -0.1, 0.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tempoMatch");
    }

    @Test
    @DisplayName("1.0 초과: IllegalArgumentException")
    void create_aboveOne_throws() {
        assertThatThrownBy(() -> new ScoreBreakdown(1.1, 0.5, 0.0, 0.0, 1.0, 0.5, 0.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScoreBreakdown(0.5, 0.5, 0.0, 0.0, 1.0, 1.1, 0.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tempoMatch");
    }

    @Test
    @DisplayName("NaN: IllegalArgumentException")
    void create_nan_throws() {
        assertThatThrownBy(() -> new ScoreBreakdown(Double.NaN, 0.5, 0.0, 0.0, 1.0, 0.5, 0.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScoreBreakdown(0.5, 0.5, 0.0, 0.0, 1.0, Double.NaN, 0.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tempoMatch");
    }

    /**
     * 1.0 초과 회귀 가드 — 기존 테스트는 {@code keyMatch}/{@code tempoMatch} 만 검증했다.
     * 가중 합산 산식이 신호별 가중치를 곱하므로 어떤 신호든 1.0을 넘으면 ranking 왜곡이 발생한다.
     * 회귀 시 어느 신호 가드가 누락됐는지 즉시 식별할 수 있도록 4신호도 각각 단언한다.
     */
    @Test
    @DisplayName("1.0 초과 (회귀 가드): rangeFit/genreMatch/moodMatch/popularity 도 각각 거부")
    void create_aboveOne_throws_remainingSignals() {
        assertThatThrownBy(() -> new ScoreBreakdown(0.5, 1.1, 0.0, 0.0, 1.0, 0.5, 0.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rangeFit");
        assertThatThrownBy(() -> new ScoreBreakdown(0.5, 0.5, 1.1, 0.0, 1.0, 0.5, 0.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("genreMatch");
        assertThatThrownBy(() -> new ScoreBreakdown(0.5, 0.5, 0.0, 1.1, 1.0, 0.5, 0.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("moodMatch");
        assertThatThrownBy(() -> new ScoreBreakdown(0.5, 0.5, 0.0, 0.0, 1.1, 0.5, 0.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("popularity");
    }

    /**
     * NaN 회귀 가드 — {@code keyMatch}/{@code tempoMatch} 외 4신호도 NaN 거부 보장.
     * NaN은 비교 연산에서 항상 false 라서 ranking 정렬을 무한 루프/순서 불안정으로 몰 수 있다.
     * 가드가 모든 신호에 적용되는지 명시 단언.
     */
    @Test
    @DisplayName("NaN (회귀 가드): rangeFit/genreMatch/moodMatch/popularity 도 각각 거부")
    void create_nan_throws_remainingSignals() {
        assertThatThrownBy(() -> new ScoreBreakdown(0.5, Double.NaN, 0.0, 0.0, 1.0, 0.5, 0.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rangeFit");
        assertThatThrownBy(() -> new ScoreBreakdown(0.5, 0.5, Double.NaN, 0.0, 1.0, 0.5, 0.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("genreMatch");
        assertThatThrownBy(() -> new ScoreBreakdown(0.5, 0.5, 0.0, Double.NaN, 1.0, 0.5, 0.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("moodMatch");
        assertThatThrownBy(() -> new ScoreBreakdown(0.5, 0.5, 0.0, 0.0, Double.NaN, 0.5, 0.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("popularity");
    }

    /**
     * {@code Double.POSITIVE_INFINITY} 회귀 가드 — {@code value > 1.0} 비교에서 양의 무한대는
     * true 라서 현재 가드로 잡힌다. 가드 코드가 {@code Double.isInfinite} 명시 검사로 바뀌어도
     * 동작이 유지되어야 한다는 invariant 단언.
     */
    @Test
    @DisplayName("POSITIVE_INFINITY (회귀 가드): 모든 신호 거부")
    void create_positiveInfinity_throws() {
        assertThatThrownBy(() -> new ScoreBreakdown(Double.POSITIVE_INFINITY, 0.5, 0.0, 0.0, 1.0, 0.5, 0.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyMatch");
        assertThatThrownBy(() -> new ScoreBreakdown(0.5, 0.5, 0.0, 0.0, 1.0, Double.POSITIVE_INFINITY, 0.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tempoMatch");
    }

    /**
     * {@code Double.NEGATIVE_INFINITY} 회귀 가드 — {@code value < 0.0} 비교에서 음의 무한대는
     * true 라서 현재 가드로 잡힌다. 위와 같은 invariant 보존 단언.
     */
    @Test
    @DisplayName("NEGATIVE_INFINITY (회귀 가드): 모든 신호 거부")
    void create_negativeInfinity_throws() {
        assertThatThrownBy(() -> new ScoreBreakdown(Double.NEGATIVE_INFINITY, 0.5, 0.0, 0.0, 1.0, 0.5, 0.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyMatch");
        assertThatThrownBy(() -> new ScoreBreakdown(0.5, 0.5, 0.0, 0.0, 1.0, Double.NEGATIVE_INFINITY, 0.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tempoMatch");
    }

    /**
     * 부동소수점 경계 invariant — {@code Double.MIN_VALUE}(약 4.9e-324, 가장 작은 양수)는 허용,
     * {@code -Double.MIN_VALUE}(가장 큰 음수에 가까운 0 미만)는 거부.
     * {@code value < 0.0} 가드가 \"음수 전부\"를 포괄하는지 정밀 단언.
     */
    @Test
    @DisplayName("부동소수점 경계 (회귀 가드): MIN_VALUE 양수 허용 / -MIN_VALUE 음수 거부")
    void create_subnormalBoundary() {
        // 양의 subnormal — 0.0 보다 크고 1.0 이내 → 허용
        new ScoreBreakdown(Double.MIN_VALUE, 0.5, 0.0, 0.0, 1.0, 0.5, 0.0, 0.0);
        // 음의 subnormal — 가드 거부
        assertThatThrownBy(() -> new ScoreBreakdown(-Double.MIN_VALUE, 0.5, 0.0, 0.0, 1.0, 0.5, 0.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyMatch");
    }
}
