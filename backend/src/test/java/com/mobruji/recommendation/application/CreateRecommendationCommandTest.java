package com.mobruji.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.song.domain.Mood;

/**
 * CreateRecommendationCommand 회귀 가드.
 *
 * <p>api.dto 레벨이 아닌 application 레벨 입력 모델(ADR 0005 §A-7)이 invariant를
 * 스스로 지켜야 함을 고정한다. 검증 위반 시 호출자(컨트롤러/스케줄러/내부 호출)
 * 어디서나 동일한 예외가 나야 한다.
 */
class CreateRecommendationCommandTest {

    @Test
    @DisplayName("정상: 필수값 + preferredBpm null 허용, excludeSongIds 빈 리스트")
    void create_valid_minimal() {
        // given / when
        final CreateRecommendationCommand command = new CreateRecommendationCommand(
                "session-1", 48, 72, Mood.CALM, null, List.of());

        // then
        assertThat(command.sessionId()).isEqualTo("session-1");
        assertThat(command.voiceRangeLow()).isEqualTo(48);
        assertThat(command.voiceRangeHigh()).isEqualTo(72);
        assertThat(command.mood()).isEqualTo(Mood.CALM);
        assertThat(command.preferredBpm()).isNull();
        assertThat(command.excludeSongIds()).isEmpty();
    }

    @Test
    @DisplayName("null guard: sessionId가 null이면 NullPointerException")
    void create_nullSessionId_throws() {
        assertThatThrownBy(() -> new CreateRecommendationCommand(
                null, 48, 72, Mood.CALM, 120, List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sessionId");
    }

    @Test
    @DisplayName("null guard: excludeSongIds가 null이면 NullPointerException")
    void create_nullExcludeSongIds_throws() {
        assertThatThrownBy(() -> new CreateRecommendationCommand(
                "session-1", 48, 72, Mood.CALM, 120, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("excludeSongIds");
    }

    @Test
    @DisplayName("불변성: 생성 후 원본 excludeSongIds를 mutate 해도 커맨드 내용은 그대로")
    void create_excludeSongIds_isDefensivelyCopied() {
        // given
        final List<Long> source = new ArrayList<>(List.of(1L, 2L));

        // when
        final CreateRecommendationCommand command = new CreateRecommendationCommand(
                "session-1", 48, 72, Mood.CALM, null, source);
        source.add(999L);

        // then
        assertThat(command.excludeSongIds()).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("불변성: 커맨드의 excludeSongIds를 직접 mutate 하면 UnsupportedOperationException")
    void create_excludeSongIds_isUnmodifiable() {
        // given
        final CreateRecommendationCommand command = new CreateRecommendationCommand(
                "session-1", 48, 72, Mood.CALM, null, List.of(1L, 2L));

        // when / then
        assertThatThrownBy(() -> command.excludeSongIds().add(3L))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("invariant: preferredBpm이 30 미만이면 IllegalArgumentException")
    void create_preferredBpmTooLow_throws() {
        assertThatThrownBy(() -> new CreateRecommendationCommand(
                "session-1", 48, 72, Mood.CALM, 29, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("preferredBpm")
                .hasMessageContaining("29");
    }

    @Test
    @DisplayName("invariant: preferredBpm이 300 초과면 IllegalArgumentException")
    void create_preferredBpmTooHigh_throws() {
        assertThatThrownBy(() -> new CreateRecommendationCommand(
                "session-1", 48, 72, Mood.CALM, 301, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("preferredBpm")
                .hasMessageContaining("301");
    }

    @Test
    @DisplayName("경계값: preferredBpm 30/300은 허용")
    void create_preferredBpmBoundary_allowed() {
        // given / when
        final CreateRecommendationCommand lower = new CreateRecommendationCommand(
                "session-1", 48, 72, Mood.CALM, 30, List.of());
        final CreateRecommendationCommand upper = new CreateRecommendationCommand(
                "session-1", 48, 72, Mood.CALM, 300, List.of());

        // then
        assertThat(lower.preferredBpm()).isEqualTo(30);
        assertThat(upper.preferredBpm()).isEqualTo(300);
    }
}
