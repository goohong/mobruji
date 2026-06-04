package com.mobruji.song.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link VocalRangeEstimate} record 불변식 및 합리성 가드(#1737) 검증 — {@link AudioAnalysisResult} 와 동일한
 * 가드 상수를 재사용하는지 확인한다 (#1778 interim 추정 음역).
 */
class VocalRangeEstimateTest {

    @Test
    @DisplayName("lowMidi > highMidi 면 IllegalArgumentException")
    void create_lowExceedsHigh_throws() {
        assertThatThrownBy(() -> new VocalRangeEstimate(70, 60, 0.3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("confidence 가 0.0~1.0 범위 밖이면 IllegalArgumentException")
    void create_confidenceOutOfRange_throws() {
        assertThatThrownBy(() -> new VocalRangeEstimate(53, 69, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VocalRangeEstimate(53, 69, -0.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("isVocalRangePlausible: 가창 한계 내 음역은 true, 밖은 false")
    void isVocalRangePlausible() {
        assertThat(new VocalRangeEstimate(53, 69, 0.3).isVocalRangePlausible()).isTrue();
        // lowMidi=30 < C2(36) — 비합리
        assertThat(new VocalRangeEstimate(30, 69, 0.3).isVocalRangePlausible()).isFalse();
        // highMidi=90 > E6(88) — 비합리
        assertThat(new VocalRangeEstimate(53, 90, 0.3).isVocalRangePlausible()).isFalse();
    }
}
