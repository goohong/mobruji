package com.mobruji.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.song.Mood;

/**
 * {@link SeedDeriver} 단위 테스트.
 *
 * <p>spec: {@code docs/features/recommendation-algorithm-v1.md} §3 비기능 — 결정성.
 */
class SeedDeriverTest {

    @Test
    @DisplayName("같은 입력은 항상 같은 seed (결정성)")
    void derive_sameInputs_returnsSameSeed() {
        // given
        final String sessionId = "session-abc";
        final int voiceRangeLow = 55;
        final int voiceRangeHigh = 75;
        final Mood mood = Mood.UPBEAT;
        // when
        final long first = SeedDeriver.derive(sessionId, voiceRangeLow, voiceRangeHigh, mood);
        final long second = SeedDeriver.derive(sessionId, voiceRangeLow, voiceRangeHigh, mood);
        // then
        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("다른 sessionId는 다른 seed (entropy 보존)")
    void derive_differentSession_returnsDifferentSeed() {
        // when
        final long a = SeedDeriver.derive("session-a", 55, 75, Mood.UPBEAT);
        final long b = SeedDeriver.derive("session-b", 55, 75, Mood.UPBEAT);
        // then
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("다른 음역대는 다른 seed")
    void derive_differentVoiceRange_returnsDifferentSeed() {
        // when
        final long a = SeedDeriver.derive("s", 55, 75, Mood.UPBEAT);
        final long b = SeedDeriver.derive("s", 56, 75, Mood.UPBEAT);
        // then
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("다른 mood는 다른 seed (null vs 값 포함)")
    void derive_differentMood_returnsDifferentSeed() {
        // when
        final long upbeat = SeedDeriver.derive("s", 55, 75, Mood.UPBEAT);
        final long calm = SeedDeriver.derive("s", 55, 75, Mood.CALM);
        final long none = SeedDeriver.derive("s", 55, 75, null);
        // then
        assertThat(upbeat).isNotEqualTo(calm);
        assertThat(upbeat).isNotEqualTo(none);
        assertThat(calm).isNotEqualTo(none);
    }

    @Test
    @DisplayName("mood=null도 일관된 seed (호출마다 흔들리지 않음)")
    void derive_nullMood_isStable() {
        // when
        final long first = SeedDeriver.derive("s", 55, 75, null);
        final long second = SeedDeriver.derive("s", 55, 75, null);
        // then
        assertThat(first).isEqualTo(second);
    }
}
