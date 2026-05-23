package com.mobruji.voice.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link MidiRange} 회귀 가드 — spec 결정 (12=C0, 119=B8) 이 코드 상수와 동기화되어 있음을 보장한다.
 *
 * <p>DTO `@Min`/`@Max` 및 두 엔티티의 validateRange 모두 이 상수를 참조하므로, 값이 의도치 않게 바뀌면 본 테스트가 가장
 * 먼저 잡는다.
 */
class MidiRangeTest {

    @Test
    @DisplayName("LOWEST_ALLOWED_MIDI 는 12 (C0)")
    void lowestAllowed_isC0() {
        assertThat(MidiRange.LOWEST_ALLOWED_MIDI).isEqualTo(12);
    }

    @Test
    @DisplayName("HIGHEST_ALLOWED_MIDI 는 119 (B8)")
    void highestAllowed_isB8() {
        assertThat(MidiRange.HIGHEST_ALLOWED_MIDI).isEqualTo(119);
    }

    @Test
    @DisplayName("범위 크기는 양수 (low < high 보장)")
    void range_isStrictlyAscending() {
        assertThat(MidiRange.LOWEST_ALLOWED_MIDI).isLessThan(MidiRange.HIGHEST_ALLOWED_MIDI);
    }
}
