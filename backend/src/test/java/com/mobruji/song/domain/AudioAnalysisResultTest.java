package com.mobruji.song.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AudioAnalysisResultTest {

    @Test
    @DisplayName("정상 입력은 record 를 그대로 생성")
    void valid_constructs() {
        // when
        final AudioAnalysisResult result = new AudioAnalysisResult(
                55, 71, "C", 120.5, 45.0, 0.78, "analyze-py-0.1.0");

        // then
        assertThat(result.lowMidi()).isEqualTo(55);
        assertThat(result.highMidi()).isEqualTo(71);
    }

    @Test
    @DisplayName("lowMidi > highMidi 면 IllegalArgumentException")
    void lowHigherThanHigh_throws() {
        assertThatThrownBy(() -> new AudioAnalysisResult(80, 60, "C", 120.0, 45.0, 0.5, "v"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lowMidi must not exceed highMidi");
    }

    @Test
    @DisplayName("confidence 가 [0,1] 밖이면 IllegalArgumentException")
    void confidenceOutOfRange_throws() {
        assertThatThrownBy(() -> new AudioAnalysisResult(50, 70, "C", 120.0, 45.0, 1.5, "v"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidence out of");
    }

    @Test
    @DisplayName("toolingVersion null 이면 NullPointerException (Objects.requireNonNull)")
    void nullToolingVersion_throws() {
        assertThatThrownBy(() -> new AudioAnalysisResult(50, 70, "C", 120.0, 45.0, 0.5, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("durationSec 음수면 IllegalArgumentException")
    void negativeDuration_throws() {
        assertThatThrownBy(() -> new AudioAnalysisResult(50, 70, "C", 120.0, -0.1, 0.5, "v"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durationSec must not be negative");
    }

    @ParameterizedTest(name = "low={0} high={1} → 합리적")
    @CsvSource({
            "55, 71", // 일반 발라드 음역
            "36, 70", // lowMidi 하한 C2(36) 경계
            "55, 88", // highMidi 상한 E6(88) 경계
            "67, 72", // lowMidi 최대 G4(67) 경계
            "47, 52", // highMidi 최소 E3(52) 경계
            "60, 65", // span 5 (단4도) 경계
            "40, 80", // span 40 경계
    })
    @DisplayName("isVocalRangePlausible: 가창 한계 안의 음역은 true")
    void plausibleRange_true(final int lowMidi, final int highMidi) {
        final AudioAnalysisResult result = new AudioAnalysisResult(
                lowMidi, highMidi, "C", 120.0, 200.0, 0.9, "v");
        assertThat(result.isVocalRangePlausible()).isTrue();
    }

    @ParameterizedTest(name = "low={0} high={1} → 비합리 ({2})")
    @CsvSource({
            "30, 70, lowMidi < C2(36) 반주 저음 오검출",
            "70, 75, lowMidi > G4(67) 최저음 과대",
            "55, 90, highMidi > E6(88) 가창 밖",
            "40, 50, highMidi < E3(52) 최고음 과소",
            "60, 63, span 3 < 5 음역폭 과소",
            "40, 82, span 42 > 40 옥타브 폴딩",
            "65, 65, low == high 음역폭 0",
    })
    @DisplayName("isVocalRangePlausible: 가창 한계를 벗어난 음역은 false (#1725)")
    void implausibleRange_false(final int lowMidi, final int highMidi, final String reason) {
        final AudioAnalysisResult result = new AudioAnalysisResult(
                lowMidi, highMidi, "C", 120.0, 200.0, 0.9, "v");
        assertThat(result.isVocalRangePlausible())
                .as(reason)
                .isFalse();
    }
}
