package com.mobruji.song.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NoteNameTest {

    @Test
    @DisplayName("MIDI 60 → C4 (middle C)")
    void of_60_isC4() {
        assertThat(NoteName.of(60)).isEqualTo("C4");
    }

    @Test
    @DisplayName("MIDI 61 → C#4 (sharp 표기, fe와 일치)")
    void of_61_isCSharp4() {
        assertThat(NoteName.of(61)).isEqualTo("C#4");
    }

    @Test
    @DisplayName("MIDI 76 → E5 (HARD 분류 임계점)")
    void of_76_isE5() {
        assertThat(NoteName.of(76)).isEqualTo("E5");
    }

    @Test
    @DisplayName("MIDI 71 → B4 (NORMAL 분류 임계점)")
    void of_71_isB4() {
        assertThat(NoteName.of(71)).isEqualTo("B4");
    }

    @Test
    @DisplayName("MIDI 12 → C0 (낮은 옥타브)")
    void of_12_isC0() {
        assertThat(NoteName.of(12)).isEqualTo("C0");
    }
}
