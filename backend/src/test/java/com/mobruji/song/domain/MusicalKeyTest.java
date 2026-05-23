package com.mobruji.song.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link MusicalKey} enum 회귀 가드.
 *
 * <p>메이저/마이너 12개씩 + UNKNOWN 1개 = 총 25개. 후속 추천 알고리즘이 키를 semitone으로 변환할 때 이 구조에 의존하므로,
 * 누락/중복 시 회귀를 즉시 잡는다.
 */
class MusicalKeyTest {

    @Test
    @DisplayName("메이저 12 + 마이너 12 + UNKNOWN 1 = 25개가 정의되어 있다")
    void values_containsExactly25Keys() {
        assertThat(MusicalKey.values()).hasSize(25);
    }

    @Test
    @DisplayName("메이저 키 12개 모두 _MAJOR 접미사를 가진다")
    void majorKeys_haveMajorSuffix() {
        final long majorCount = Arrays.stream(MusicalKey.values())
                .filter(musicalKey -> musicalKey.name().endsWith("_MAJOR"))
                .count();
        assertThat(majorCount).isEqualTo(12L);
    }

    @Test
    @DisplayName("마이너 키 12개 모두 _MINOR 접미사를 가진다")
    void minorKeys_haveMinorSuffix() {
        final long minorCount = Arrays.stream(MusicalKey.values())
                .filter(musicalKey -> musicalKey.name().endsWith("_MINOR"))
                .count();
        assertThat(minorCount).isEqualTo(12L);
    }

    @Test
    @DisplayName("UNKNOWN 키가 존재한다 (시드 미상 곡 fallback)")
    void unknown_isDefined() {
        assertThat(MusicalKey.valueOf("UNKNOWN")).isEqualTo(MusicalKey.UNKNOWN);
    }

    @Test
    @DisplayName("대표 키 valueOf 복원: C_MAJOR / A_MINOR")
    void valueOf_representativeKeys_roundTrip() {
        assertThat(MusicalKey.valueOf("C_MAJOR")).isEqualTo(MusicalKey.C_MAJOR);
        assertThat(MusicalKey.valueOf("A_MINOR")).isEqualTo(MusicalKey.A_MINOR);
    }
}
