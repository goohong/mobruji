package com.mobruji.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mobruji.recommendation.application.RecommendationService.RecommendationHistorySnapshot;
import com.mobruji.recommendation.domain.RecommendationRequestEntity;
import com.mobruji.recommendation.infrastructure.RecommendationRepository;
import com.mobruji.recommendation.infrastructure.RecommendationRequestRepository;
import com.mobruji.song.domain.Mood;
import com.mobruji.song.infrastructure.SongRepository;

/**
 * {@link RecommendationService#readHistoryBySessionId} snapshot 이 영속된
 * {@code excludeSongIds} 를 entity 차원에서 정확히 보존해 노출하는지 회귀 가드.
 *
 * <p>배경: #218 PR 로 {@code RecommendationRequestEntity.excludeSongIds} 가 영속됐고,
 * history 조회 결과의 {@link RecommendationHistorySnapshot#request()} 는 해당 entity 를
 * 그대로 노출한다. 추후 service refactor 가 history 측에서 excludeSongIds 를 끊거나
 * 다른 컬렉션과 섞이는 회귀가 발생해도 빌드가 통과하지 않도록 잠근다.
 *
 * <p>spec: {@code docs/features/recommendation-history-and-feedback.md} §5-2.
 *
 * <p>integration 통합 (DB round-trip 검증) 은 이미
 * {@code RecommendationExcludeSongIdsTest#excludeSongIds_persistedOnRequestEntity}
 * 가 커버하므로 본 가드는 service 레이어 단위(Mockito) 만 다룬다.
 */
@ExtendWith(MockitoExtension.class)
class RecommendationServiceHistoryExcludeSongIdsTest {

    @Mock
    private RecommendationRequestRepository recommendationRequestRepository;

    @Mock
    private RecommendationRepository recommendationRepository;

    @Mock
    private SongRepository songRepository;

    @Mock
    private RecommendationScorer recommendationScorer;

    @Mock
    private DiversityPostProcessor diversityPostProcessor;

    @Mock
    private RecommendationProperties recommendationProperties;

    @InjectMocks
    private RecommendationService recommendationService;

    @Test
    @DisplayName("readHistoryBySessionId: snapshot.request 에 영속된 excludeSongIds 가 그대로 보존된다 (다중 곡)")
    void readHistory_preservesPersistedExcludeSongIds() throws Exception {
        // given: 추천 요청 1건이 BTS Dynamite(101) + IU Eight(202) 두 곡 제외로 영속됨.
        final RecommendationRequestEntity request = buildPersistedRequest(
                10L, "session-a", List.of(101L, 202L));
        given(recommendationRequestRepository.findBySessionIdOrderByCreatedAtDescIdDesc("session-a"))
                .willReturn(List.of(request));
        given(recommendationRepository.findByRecommendationRequestIdIn(List.of(10L)))
                .willReturn(List.of());

        // when
        final List<RecommendationHistorySnapshot> snapshots = recommendationService
                .readHistoryBySessionId("session-a");

        // then: snapshot 의 request entity 는 영속된 excludeSongIds 를 그대로 노출.
        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.get(0).request().getExcludeSongIds())
                .containsExactlyInAnyOrder(101L, 202L);
    }

    @Test
    @DisplayName("readHistoryBySessionId: excludeSongIds 없이 영속된 요청은 빈 리스트로 노출된다 (null 노출 금지)")
    void readHistory_emptyExcludeSongIds_isEmptyList() throws Exception {
        // given
        final RecommendationRequestEntity request = buildPersistedRequest(
                11L, "session-empty", List.of());
        given(recommendationRequestRepository.findBySessionIdOrderByCreatedAtDescIdDesc("session-empty"))
                .willReturn(List.of(request));
        given(recommendationRepository.findByRecommendationRequestIdIn(List.of(11L)))
                .willReturn(List.of());

        // when
        final List<RecommendationHistorySnapshot> snapshots = recommendationService
                .readHistoryBySessionId("session-empty");

        // then: null 이 아니라 빈 리스트로 노출 (Collection 류 invariant — fe 에서 size() 호출 안전).
        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.get(0).request().getExcludeSongIds()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("readHistoryBySessionId: 여러 요청의 excludeSongIds 가 서로 섞이지 않고 각 snapshot 에 정확히 분리된다")
    void readHistory_excludeSongIds_isolatedAcrossRequests() throws Exception {
        // given: 두 요청 — 각자 다른 제외 곡 셋.
        final RecommendationRequestEntity newer = buildPersistedRequest(
                21L, "session-x", List.of(303L));
        final RecommendationRequestEntity older = buildPersistedRequest(
                20L, "session-x", List.of(101L, 202L));
        // service 는 createdAt DESC + id DESC 정렬을 repository 에 위임하므로 mock 도 같은 순서.
        given(recommendationRequestRepository.findBySessionIdOrderByCreatedAtDescIdDesc("session-x"))
                .willReturn(List.of(newer, older));
        given(recommendationRepository.findByRecommendationRequestIdIn(List.of(21L, 20L)))
                .willReturn(List.of());

        // when
        final List<RecommendationHistorySnapshot> snapshots = recommendationService
                .readHistoryBySessionId("session-x");

        // then: 각 snapshot 의 entity 는 자기 요청에 영속된 excludeSongIds 만 가진다.
        assertThat(snapshots).hasSize(2);
        assertThat(snapshots.get(0).request().getId()).isEqualTo(21L);
        assertThat(snapshots.get(0).request().getExcludeSongIds()).containsExactlyInAnyOrder(303L);
        assertThat(snapshots.get(1).request().getId()).isEqualTo(20L);
        assertThat(snapshots.get(1).request().getExcludeSongIds()).containsExactlyInAnyOrder(101L, 202L);
    }

    /**
     * persistence 흉내: id/createdAt 까지 채워진 entity 를 반환 (mock save 후 상태와 동등).
     */
    private static RecommendationRequestEntity buildPersistedRequest(
            final long id, final String sessionId, final List<Long> excludeSongIds) throws Exception {
        final RecommendationRequestEntity entity = RecommendationRequestEntity.create(
                sessionId, 50, 75, Mood.UPBEAT, null, excludeSongIds);
        final Field idField = RecommendationRequestEntity.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(entity, id);
        final Field createdAtField = RecommendationRequestEntity.class.getDeclaredField("createdAt");
        createdAtField.setAccessible(true);
        createdAtField.set(entity, LocalDateTime.now());
        return entity;
    }
}
