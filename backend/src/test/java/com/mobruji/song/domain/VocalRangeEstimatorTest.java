package com.mobruji.song.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link VocalRangeEstimator} 단위 테스트 — keyOriginal/genre 메타로부터의 음역대 추정 휴리스틱과 합리성 가드(#1737)를
 * 검증한다 (오디오 자체분석 인프라 부재 동안의 interim 추정기, #1778).
 */
class VocalRangeEstimatorTest {

    @Test
    @DisplayName("키 root 중심 ±오프셋으로 음역대를 산출하고 합리성 가드를 통과한다")
    void estimate_validKey_returnsPlausibleRange() {
        final Optional<VocalRangeEstimate> estimate = VocalRangeEstimator.estimate(MusicalKey.C_MAJOR, null);

        assertThat(estimate).isPresent();
        // C4=60, low=60-7=53, high=60+9+0(genre null)=69
        assertThat(estimate.get().lowMidi()).isEqualTo(53);
        assertThat(estimate.get().highMidi()).isEqualTo(69);
        assertThat(estimate.get().confidence()).isEqualTo(VocalRangeEstimator.ESTIMATED_CONFIDENCE);
        assertThat(estimate.get().isVocalRangePlausible()).isTrue();
    }

    @Test
    @DisplayName("genre 별 최고음 오프셋이 반영된다 — 발라드/락은 climax 가 높아 +3")
    void estimate_genreOffset_raisesHighMidi() {
        final Optional<VocalRangeEstimate> ballad = VocalRangeEstimator.estimate(MusicalKey.C_MAJOR, "발라드");
        final Optional<VocalRangeEstimate> dance = VocalRangeEstimator.estimate(MusicalKey.C_MAJOR, "댄스");
        final Optional<VocalRangeEstimate> trot = VocalRangeEstimator.estimate(MusicalKey.C_MAJOR, "트로트");

        assertThat(ballad).get().extracting(VocalRangeEstimate::highMidi).isEqualTo(72);
        assertThat(dance).get().extracting(VocalRangeEstimate::highMidi).isEqualTo(70);
        assertThat(trot).get().extracting(VocalRangeEstimate::highMidi).isEqualTo(69);
    }

    @Test
    @DisplayName("키가 UNKNOWN 이면 중심을 잡을 수 없어 추정 불가 (empty)")
    void estimate_unknownKey_returnsEmpty() {
        assertThat(VocalRangeEstimator.estimate(MusicalKey.UNKNOWN, "발라드")).isEmpty();
    }

    @Test
    @DisplayName("키가 null 이면 추정 불가 (empty)")
    void estimate_nullKey_returnsEmpty() {
        assertThat(VocalRangeEstimator.estimate(null, "발라드")).isEmpty();
    }

    @Test
    @DisplayName("미상/null genre 는 오프셋 0 으로 기본 음역폭")
    void estimate_unknownGenre_zeroOffset() {
        final Optional<VocalRangeEstimate> unknown = VocalRangeEstimator.estimate(MusicalKey.G_MAJOR, "재즈");

        // G4=67, low=60, high=67+9+0=76
        assertThat(unknown).get().extracting(VocalRangeEstimate::lowMidi).isEqualTo(60);
        assertThat(unknown).get().extracting(VocalRangeEstimate::highMidi).isEqualTo(76);
        assertThat(unknown.get().isVocalRangePlausible()).isTrue();
    }
}
