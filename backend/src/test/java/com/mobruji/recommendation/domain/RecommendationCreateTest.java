package com.mobruji.recommendation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecommendationCreateTest {

    @Test
    @DisplayName("정상: 필수 인자 전체 + rankPosition >= 1 이면 createdAt이 자동 채워진다")
    void create_valid_setsCreatedAt() {
        // given / when
        final Recommendation recommendation = Recommendation.create(1L, 2L, 0.87, "key+range match", 1);

        // then
        assertThat(recommendation.getRecommendationRequestId()).isEqualTo(1L);
        assertThat(recommendation.getSongId()).isEqualTo(2L);
        assertThat(recommendation.getScore()).isEqualTo(0.87);
        assertThat(recommendation.getMatchReason()).isEqualTo("key+range match");
        assertThat(recommendation.getRankPosition()).isEqualTo(1);
        assertThat(recommendation.getCreatedAt()).isNotNull();
        assertThat(recommendation.getId()).isNull();
    }

    @Test
    @DisplayName("null guard: recommendationRequestId가 null이면 NullPointerException")
    void create_nullRequestId_throws() {
        assertThatThrownBy(() -> Recommendation.create(null, 2L, 0.5, "reason", 1))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("recommendationRequestId");
    }

    @Test
    @DisplayName("null guard: songId가 null이면 NullPointerException")
    void create_nullSongId_throws() {
        assertThatThrownBy(() -> Recommendation.create(1L, null, 0.5, "reason", 1))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("songId");
    }

    @Test
    @DisplayName("null guard: matchReason이 null이면 NullPointerException")
    void create_nullMatchReason_throws() {
        assertThatThrownBy(() -> Recommendation.create(1L, 2L, 0.5, null, 1))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("matchReason");
    }

    @Test
    @DisplayName("invariant: rankPosition이 0 이하이면 IllegalArgumentException")
    void create_invalidRank_throws() {
        assertThatThrownBy(() -> Recommendation.create(1L, 2L, 0.5, "reason", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rankPosition");
        assertThatThrownBy(() -> Recommendation.create(1L, 2L, 0.5, "reason", -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rankPosition");
    }
}
