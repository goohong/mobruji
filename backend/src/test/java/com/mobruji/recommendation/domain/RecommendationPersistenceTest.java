package com.mobruji.recommendation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.mobruji.recommendation.infrastructure.RecommendationRepository;

/**
 * {@link Recommendation} JPA 엔티티 invariant 회귀 가드.
 *
 * <p>spec: {@code docs/features/recommendation-algorithm-v1.md} §5 — 추천 결과 row는 요청 ID, 곡 ID, 점수,
 * 사유, 랭크를 보존한다. 동일 요청 내 결과는 {@code rankPosition} 오름차순으로 조회된다.
 *
 * <p>scope: persistence round-trip만. 도메인 팩토리 가드는 {@link RecommendationCreateTest}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RecommendationPersistenceTest {

    @Autowired
    private RecommendationRepository recommendationRepository;

    @Test
    @DisplayName("score, matchReason, rankPosition 모두 save → findById 라운드트립 시 동일 값으로 보존된다")
    void allFields_roundTripIntact() {
        // given
        final Recommendation recommendation = Recommendation.create(100L, 200L, 0.876, "key+range match", 3);

        // when
        final Recommendation saved = recommendationRepository.save(recommendation);
        recommendationRepository.flush();
        final Recommendation loaded = recommendationRepository.findById(saved.getId()).orElseThrow();

        // then
        assertThat(loaded.getRecommendationRequestId()).isEqualTo(100L);
        assertThat(loaded.getSongId()).isEqualTo(200L);
        assertThat(loaded.getScore()).isEqualTo(0.876);
        assertThat(loaded.getMatchReason()).isEqualTo("key+range match");
        assertThat(loaded.getRankPosition()).isEqualTo(3);
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("score 0.0 경계값도 라운드트립 시 보존된다")
    void score_zeroBoundary_roundTrips() {
        // given
        final Recommendation recommendation = Recommendation.create(101L, 201L, 0.0, "zero score", 1);

        // when
        final Recommendation saved = recommendationRepository.save(recommendation);
        recommendationRepository.flush();
        final Recommendation loaded = recommendationRepository.findById(saved.getId()).orElseThrow();

        // then
        assertThat(loaded.getScore()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("score 1.0 상한 경계값도 라운드트립 시 보존된다")
    void score_oneBoundary_roundTrips() {
        // given
        final Recommendation recommendation = Recommendation.create(102L, 202L, 1.0, "max score", 1);

        // when
        final Recommendation saved = recommendationRepository.save(recommendation);
        recommendationRepository.flush();
        final Recommendation loaded = recommendationRepository.findById(saved.getId()).orElseThrow();

        // then
        assertThat(loaded.getScore()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("rankPosition 1 (최상위)도 라운드트립 시 보존된다")
    void rankPosition_one_roundTrips() {
        // given
        final Recommendation recommendation = Recommendation.create(103L, 203L, 0.5, "top rank", 1);

        // when
        final Recommendation saved = recommendationRepository.save(recommendation);
        recommendationRepository.flush();
        final Recommendation loaded = recommendationRepository.findById(saved.getId()).orElseThrow();

        // then
        assertThat(loaded.getRankPosition()).isEqualTo(1);
    }

    @Test
    @DisplayName("matchReason 200자 상한 경계값도 라운드트립 시 잘리지 않고 보존된다")
    void matchReason_maxLength_roundTrips() {
        // given
        final String maxReason = "x".repeat(200);
        final Recommendation recommendation = Recommendation.create(104L, 204L, 0.5, maxReason, 1);

        // when
        final Recommendation saved = recommendationRepository.save(recommendation);
        recommendationRepository.flush();
        final Recommendation loaded = recommendationRepository.findById(saved.getId()).orElseThrow();

        // then
        assertThat(loaded.getMatchReason()).hasSize(200).isEqualTo(maxReason);
    }

    @Test
    @DisplayName("findByRecommendationRequestIdOrderByRankPositionAsc는 rankPosition 오름차순으로 정렬한다")
    void findByRequestId_orderedByRankPositionAsc() {
        // given: 삽입 순서를 일부러 뒤섞어 정렬이 DB쪽에서 보장됨을 검증
        final long requestId = 999L;
        recommendationRepository.save(Recommendation.create(requestId, 11L, 0.7, "third", 3));
        recommendationRepository.save(Recommendation.create(requestId, 12L, 0.9, "first", 1));
        recommendationRepository.save(Recommendation.create(requestId, 13L, 0.8, "second", 2));
        recommendationRepository.flush();

        // when
        final List<Recommendation> loaded = recommendationRepository
                .findByRecommendationRequestIdOrderByRankPositionAsc(requestId);

        // then
        assertThat(loaded).extracting(Recommendation::getRankPosition).containsExactly(1, 2, 3);
        assertThat(loaded).extracting(Recommendation::getSongId).containsExactly(12L, 13L, 11L);
    }

    @Test
    @DisplayName("다른 requestId의 결과는 findByRecommendationRequestIdOrderByRankPositionAsc에 섞이지 않는다")
    void findByRequestId_isolatesByRequestId() {
        // given
        recommendationRepository.save(Recommendation.create(881L, 21L, 0.5, "a", 1));
        recommendationRepository.save(Recommendation.create(881L, 22L, 0.5, "a", 2));
        recommendationRepository.save(Recommendation.create(882L, 31L, 0.5, "b", 1));
        recommendationRepository.flush();

        // when
        final List<Recommendation> loaded = recommendationRepository
                .findByRecommendationRequestIdOrderByRankPositionAsc(881L);

        // then
        assertThat(loaded).hasSize(2);
        assertThat(loaded).allMatch(r -> r.getRecommendationRequestId().equals(881L));
    }
}
