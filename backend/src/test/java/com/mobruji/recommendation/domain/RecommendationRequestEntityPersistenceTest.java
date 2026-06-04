package com.mobruji.recommendation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.mobruji.song.domain.Mood;

import com.mobruji.recommendation.infrastructure.RecommendationRequestRepository;

/**
 * {@link RecommendationRequestEntity}의 {@code excludeSongIds} 영속화 라운드트립.
 *
 * <p>spec: {@code docs/features/recommendation-algorithm-v1.md} §5-1 — {@code excludeSongIds}는 요청과 함께
 * 영속한다. join table {@code recommendation_request_exclude_song}에 곡 ID별 row로 저장.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RecommendationRequestEntityPersistenceTest {

    @Autowired
    private RecommendationRequestRepository recommendationRequestRepository;

    @Test
    @DisplayName("excludeSongIds는 save → findById 라운드트립 시 동일 순서로 보존된다")
    void excludeSongIds_roundTripsThroughJoinTable() {
        // given
        final List<Long> excludeSongIds = List.of(101L, 202L, 303L);
        final RecommendationRequestEntity entity = RecommendationRequestEntity.create(
                "round-trip-session", 50, 80, Mood.UPBEAT, null, null, null, excludeSongIds);

        // when
        final RecommendationRequestEntity saved = recommendationRequestRepository.save(entity);
        recommendationRequestRepository.flush();
        final RecommendationRequestEntity loaded = recommendationRequestRepository.findById(saved.getId())
                .orElseThrow();

        // then: 입력 순서 그대로 보존 (@ElementCollection 기본 List 의미)
        assertThat(loaded.getExcludeSongIds()).containsExactly(101L, 202L, 303L);
    }

    @Test
    @DisplayName("preferredBpm은 save → findById 라운드트립 시 동일 값으로 보존된다 (v2 #218)")
    void preferredBpm_roundTrips() {
        // given
        final RecommendationRequestEntity entity = RecommendationRequestEntity.create(
                "bpm-session", 50, 80, Mood.UPBEAT, 128, null, null, List.of());

        // when
        final RecommendationRequestEntity saved = recommendationRequestRepository.save(entity);
        recommendationRequestRepository.flush();
        final RecommendationRequestEntity loaded = recommendationRequestRepository.findById(saved.getId())
                .orElseThrow();

        // then
        assertThat(loaded.getPreferredBpm()).isEqualTo(128);
    }

    @Test
    @DisplayName("preferredBpm null도 라운드트립 시 null로 복원된다 (v2 #218)")
    void preferredBpm_nullRoundTripsAsNull() {
        // given
        final RecommendationRequestEntity entity = RecommendationRequestEntity.create(
                "bpm-null-session", 50, 80, Mood.UPBEAT, null, null, null, List.of());

        // when
        final RecommendationRequestEntity saved = recommendationRequestRepository.save(entity);
        recommendationRequestRepository.flush();
        final RecommendationRequestEntity loaded = recommendationRequestRepository.findById(saved.getId())
                .orElseThrow();

        // then
        assertThat(loaded.getPreferredBpm()).isNull();
    }

    @Test
    @DisplayName("빈 excludeSongIds도 round-trip 시 빈 컬렉션으로 복원된다")
    void excludeSongIds_emptyListRoundTripsAsEmpty() {
        // given
        final RecommendationRequestEntity entity = RecommendationRequestEntity.create(
                "empty-session", 50, 80, Mood.UPBEAT, null, null, null, List.of());

        // when
        final RecommendationRequestEntity saved = recommendationRequestRepository.save(entity);
        recommendationRequestRepository.flush();
        final RecommendationRequestEntity loaded = recommendationRequestRepository.findById(saved.getId())
                .orElseThrow();

        // then
        assertThat(loaded.getExcludeSongIds()).isEmpty();
    }
}
