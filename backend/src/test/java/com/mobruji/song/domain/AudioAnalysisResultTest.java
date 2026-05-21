package com.mobruji.song.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
