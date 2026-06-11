package com.mobruji.recommendation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.song.domain.Mood;

/**
 * {@link RecommendationRequestEntity#create} factory invariant 회귀 가드.
 *
 * <p>persistence 라운드트립은 {@link RecommendationRequestEntityPersistenceTest} 가 담당하며,
 * 본 테스트는 인메모리 단위 invariant 만 단언한다. (cf. ScoreBreakdown #707 — 가드 회귀를
 * 정밀하게 잡기 위한 단위 분리.)
 *
 * <p>가드 대상:
 * <ul>
 * <li>sessionId / excludeSongIds null 거부</li>
 * <li>voiceRangeLow &lt;= voiceRangeHigh 강제 (경계 동일 허용)</li>
 * <li>preferredBpm nullable + [30, 300] 범위</li>
 * <li>excludeSongIds 방어적 복사 (원본 변형이 내부에 새지 않음)</li>
 * </ul>
 */
class RecommendationRequestEntityCreateTest {

    @Test
    @DisplayName("정상: 전 필드 유효하면 createdAt 자동 채워지고 excludeSongIds가 보존된다")
    void create_valid_setsCreatedAtAndExcludeIds() {
        // given / when
        final RecommendationRequestEntity entity = RecommendationRequestEntity.create(
                "sess-1", 50, 70, Mood.UPBEAT, 120, null, List.of(10L, 20L));

        // then
        assertThat(entity.getSessionId()).isEqualTo("sess-1");
        assertThat(entity.getVoiceRangeLow()).isEqualTo(50);
        assertThat(entity.getVoiceRangeHigh()).isEqualTo(70);
        assertThat(entity.getMood()).isEqualTo(Mood.UPBEAT);
        assertThat(entity.getPreferredBpm()).isEqualTo(120);
        assertThat(entity.getCreatedAt()).isNotNull();
        assertThat(entity.getId()).isNull();
        assertThat(entity.getExcludeSongIds()).containsExactly(10L, 20L);
    }

    @Test
    @DisplayName("null guard: sessionId가 null이면 NullPointerException")
    void create_nullSessionId_throws() {
        assertThatThrownBy(() -> RecommendationRequestEntity.create(
                null, 50, 70, Mood.UPBEAT, 120, null, List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sessionId");
    }

    @Test
    @DisplayName("null guard: excludeSongIds가 null이면 NullPointerException")
    void create_nullExcludeSongIds_throws() {
        assertThatThrownBy(() -> RecommendationRequestEntity.create(
                "sess-1", 50, 70, Mood.UPBEAT, 120, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("excludeSongIds");
    }

    @Test
    @DisplayName("invariant: voiceRangeLow > voiceRangeHigh 면 IllegalArgumentException")
    void create_invertedRange_throws() {
        assertThatThrownBy(() -> RecommendationRequestEntity.create(
                "sess-1", 80, 60, Mood.UPBEAT, 120, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("voiceRangeLow")
                .hasMessageContaining("voiceRangeHigh");
    }

    @Test
    @DisplayName("경계 (회귀 가드): voiceRangeLow == voiceRangeHigh 인 단일 노트도 허용 (flat single-note range)")
    void create_equalRange_allowed() {
        // 단일 노트 voice range도 영속 가능해야 한다 (특수 케이스: 분석 실패 fallback 등).
        // 가드가 `<` 가 아닌 `>` 라는 invariant 보존.
        final RecommendationRequestEntity entity = RecommendationRequestEntity.create(
                "sess-1", 60, 60, Mood.UPBEAT, 120, null, List.of());

        assertThat(entity.getVoiceRangeLow()).isEqualTo(60);
        assertThat(entity.getVoiceRangeHigh()).isEqualTo(60);
    }

    @Test
    @DisplayName("preferredBpm nullable: null이면 mood 기반 default 사용 의도, 생성은 통과")
    void create_nullPreferredBpm_allowed() {
        // 도메인 docstring(v2 #218): "nullable — 미입력 시 mood 기반 default 사용".
        // null 거부로 가드가 좁아지면 회귀.
        final RecommendationRequestEntity entity = RecommendationRequestEntity.create(
                "sess-1", 50, 70, Mood.UPBEAT, null, null, List.of());

        assertThat(entity.getPreferredBpm()).isNull();
    }

    @Test
    @DisplayName("preferredBpm 범위 (회귀 가드): 30 미만이면 IllegalArgumentException")
    void create_preferredBpmBelowMin_throws() {
        assertThatThrownBy(() -> RecommendationRequestEntity.create(
                "sess-1", 50, 70, Mood.UPBEAT, 29, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("preferredBpm");
    }

    @Test
    @DisplayName("preferredBpm 범위 (회귀 가드): 300 초과면 IllegalArgumentException")
    void create_preferredBpmAboveMax_throws() {
        assertThatThrownBy(() -> RecommendationRequestEntity.create(
                "sess-1", 50, 70, Mood.UPBEAT, 301, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("preferredBpm");
    }

    @Test
    @DisplayName("preferredBpm 경계 (회귀 가드): 30 / 300 정확히 허용")
    void create_preferredBpmBoundary_allowed() {
        final RecommendationRequestEntity low = RecommendationRequestEntity.create(
                "sess-1", 50, 70, Mood.UPBEAT, 30, null, List.of());
        final RecommendationRequestEntity high = RecommendationRequestEntity.create(
                "sess-1", 50, 70, Mood.UPBEAT, 300, null, List.of());

        assertThat(low.getPreferredBpm()).isEqualTo(30);
        assertThat(high.getPreferredBpm()).isEqualTo(300);
    }

    @Test
    @DisplayName("방어적 복사 (회귀 가드): 입력 리스트 변형이 내부 excludeSongIds에 새지 않는다")
    void create_defensiveCopy_isolatesMutation() {
        // given: 가변 리스트로 전달
        final List<Long> mutable = new ArrayList<>();
        mutable.add(100L);
        final RecommendationRequestEntity entity = RecommendationRequestEntity.create(
                "sess-1", 50, 70, Mood.UPBEAT, 120, null, mutable);

        // when: 원본을 변형
        mutable.add(200L);
        mutable.clear();

        // then: 내부 리스트는 원본 변형과 무관해야 한다
        assertThat(entity.getExcludeSongIds()).containsExactly(100L);
    }
}
