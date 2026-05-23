package com.mobruji.song.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Difficulty} enum 회귀 가드.
 *
 * <p>fe(`web/lib/difficulty.ts`)와 1:1 일치 + 추천 카드 UI 라벨이라 name 변경/순서 변경은 호환성을 깨뜨린다.
 * 분류 경계(HARD/NORMAL/EASY) 회귀 가드는 {@link SongTest#deriveDifficulty_high76_isHard} 등에서 별도로 수행.
 */
class DifficultyTest {

    @Test
    @DisplayName("세 단계가 정의되어 있다 (EASY/NORMAL/HARD)")
    void values_containsExactlyThreeLevels() {
        assertThat(Difficulty.values())
                .hasSize(3)
                .containsExactlyInAnyOrder(Difficulty.EASY, Difficulty.NORMAL, Difficulty.HARD);
    }

    @Test
    @DisplayName("선언 순서는 EASY → NORMAL → HARD (난이도 오름차순)")
    void declarationOrder_isAscending() {
        assertThat(Difficulty.values())
                .containsExactly(Difficulty.EASY, Difficulty.NORMAL, Difficulty.HARD);
    }

    @Test
    @DisplayName("valueOf로 EASY 복원 가능")
    void valueOf_easy_roundTrips() {
        assertThat(Difficulty.valueOf("EASY")).isEqualTo(Difficulty.EASY);
    }

    @Test
    @DisplayName("valueOf로 NORMAL 복원 가능")
    void valueOf_normal_roundTrips() {
        assertThat(Difficulty.valueOf("NORMAL")).isEqualTo(Difficulty.NORMAL);
    }

    @Test
    @DisplayName("valueOf로 HARD 복원 가능")
    void valueOf_hard_roundTrips() {
        assertThat(Difficulty.valueOf("HARD")).isEqualTo(Difficulty.HARD);
    }
}
