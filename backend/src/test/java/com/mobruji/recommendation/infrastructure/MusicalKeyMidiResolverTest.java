package com.mobruji.recommendation.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.mobruji.song.domain.MusicalKey;

/**
 * v1 voiceRangeFit 휴리스틱의 루트 매핑 회귀 가드.
 *
 * <p>RecommendationScorer.voiceRangeFit이 직접 의존하며, 매핑이 흔들리면 추천 결과 결정성이 깨진다.
 * 24개 키 + UNKNOWN + 상수까지 잠가둔다.
 */
class MusicalKeyMidiResolverTest {

    @ParameterizedTest(name = "{0} → MIDI {1}")
    @DisplayName("rootMidi: 24개 키 각각 MIDI 60(C4)~71(B4) 범위 결정성")
    @CsvSource({
            "C_MAJOR, 60",
            "C_SHARP_MAJOR, 61",
            "D_MAJOR, 62",
            "D_SHARP_MAJOR, 63",
            "E_MAJOR, 64",
            "F_MAJOR, 65",
            "F_SHARP_MAJOR, 66",
            "G_MAJOR, 67",
            "G_SHARP_MAJOR, 68",
            "A_MAJOR, 69",
            "A_SHARP_MAJOR, 70",
            "B_MAJOR, 71",
            "C_MINOR, 60",
            "C_SHARP_MINOR, 61",
            "D_MINOR, 62",
            "D_SHARP_MINOR, 63",
            "E_MINOR, 64",
            "F_MINOR, 65",
            "F_SHARP_MINOR, 66",
            "G_MINOR, 67",
            "G_SHARP_MINOR, 68",
            "A_MINOR, 69",
            "A_SHARP_MINOR, 70",
            "B_MINOR, 71"
    })
    void rootMidi_allKnownKeys_returnExpectedMidi(final MusicalKey musicalKey, final int expectedMidi) {
        // given/when
        final int actualMidi = MusicalKeyMidiResolver.rootMidi(musicalKey);
        // then
        assertThat(actualMidi).isEqualTo(expectedMidi);
    }

    @Test
    @DisplayName("rootMidi: 동일 root는 메이저/마이너 구분 없이 같은 MIDI (v1 단순화)")
    void rootMidi_majorMinor_shareSameRoot() {
        // given/when/then: 12 root × {MAJOR, MINOR}
        assertThat(MusicalKeyMidiResolver.rootMidi(MusicalKey.C_MAJOR))
                .isEqualTo(MusicalKeyMidiResolver.rootMidi(MusicalKey.C_MINOR));
        assertThat(MusicalKeyMidiResolver.rootMidi(MusicalKey.F_SHARP_MAJOR))
                .isEqualTo(MusicalKeyMidiResolver.rootMidi(MusicalKey.F_SHARP_MINOR));
        assertThat(MusicalKeyMidiResolver.rootMidi(MusicalKey.A_MAJOR))
                .isEqualTo(MusicalKeyMidiResolver.rootMidi(MusicalKey.A_MINOR));
        assertThat(MusicalKeyMidiResolver.rootMidi(MusicalKey.B_MAJOR))
                .isEqualTo(MusicalKeyMidiResolver.rootMidi(MusicalKey.B_MINOR));
    }

    @Test
    @DisplayName("rootMidi: UNKNOWN은 -1 (Scorer.voiceRangeFit의 중립 분기 트리거)")
    void rootMidi_unknown_returnsMinusOne() {
        // given/when
        final int rootMidi = MusicalKeyMidiResolver.rootMidi(MusicalKey.UNKNOWN);
        // then: -1 이어야 RecommendationScorer.voiceRangeFit이 0.5 중립 반환
        assertThat(rootMidi).isEqualTo(-1);
    }

    @Test
    @DisplayName("결정성: 동일 키 반복 호출 시 항상 동일 MIDI")
    void rootMidi_isDeterministic() {
        // given
        final MusicalKey musicalKey = MusicalKey.G_MAJOR;
        // when
        final int firstCall = MusicalKeyMidiResolver.rootMidi(musicalKey);
        final int secondCall = MusicalKeyMidiResolver.rootMidi(musicalKey);
        final int thirdCall = MusicalKeyMidiResolver.rootMidi(musicalKey);
        // then
        assertThat(firstCall).isEqualTo(secondCall).isEqualTo(thirdCall).isEqualTo(67);
    }

    @Test
    @DisplayName("LOW_OFFSET/HIGH_OFFSET 상수 잠금: v1 root±7 semitones (완전 5도) 휴리스틱")
    void offsetConstants_areLocked() {
        // given/when/then: 변경 시 voiceRangeFit 회귀 영향이 큼 → 상수 자체를 회귀 가드
        assertThat(MusicalKeyMidiResolver.LOW_OFFSET).isEqualTo(-7);
        assertThat(MusicalKeyMidiResolver.HIGH_OFFSET).isEqualTo(7);
    }

    @Test
    @DisplayName("음역 범위 폭: HIGH_OFFSET - LOW_OFFSET = 14 semitones (1 옥타브 + 장2도)")
    void offsetSpan_isFourteenSemitones() {
        // given/when
        final int span = MusicalKeyMidiResolver.HIGH_OFFSET - MusicalKeyMidiResolver.LOW_OFFSET;
        // then: voiceRangeFit songSpan 분모로 사용되므로 0이면 0 나눗셈 분기 발생
        assertThat(span).isEqualTo(14);
    }
}
