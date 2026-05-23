package com.mobruji.song.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Mood} enum 회귀 가드.
 *
 * <p>DB(`mood` 컬럼)에 enum name이 저장되고 추천 매칭의 moodMatch 입력으로 쓰여, value 추가/이름 변경은
 * 시드/추천 양쪽 호환성을 깨뜨린다.
 */
class MoodTest {

    @Test
    @DisplayName("v1 mood 6종이 정의되어 있다")
    void values_containsExactlySixMoods() {
        assertThat(Mood.values())
                .hasSize(6)
                .containsExactlyInAnyOrder(
                        Mood.UPBEAT,
                        Mood.CALM,
                        Mood.EMOTIONAL,
                        Mood.POWERFUL,
                        Mood.GROOVY,
                        Mood.NOSTALGIC);
    }

    @Test
    @DisplayName("valueOf로 UPBEAT 복원 가능")
    void valueOf_upbeat_roundTrips() {
        assertThat(Mood.valueOf("UPBEAT")).isEqualTo(Mood.UPBEAT);
    }

    @Test
    @DisplayName("valueOf로 NOSTALGIC 복원 가능")
    void valueOf_nostalgic_roundTrips() {
        assertThat(Mood.valueOf("NOSTALGIC")).isEqualTo(Mood.NOSTALGIC);
    }
}
