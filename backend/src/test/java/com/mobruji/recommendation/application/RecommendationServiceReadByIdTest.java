package com.mobruji.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mobruji.recommendation.domain.Recommendation;
import com.mobruji.recommendation.domain.RecommendationNotFoundException;
import com.mobruji.recommendation.domain.RecommendationRequestEntity;
import com.mobruji.recommendation.domain.RecommendationResult;
import com.mobruji.recommendation.infrastructure.RecommendationRepository;
import com.mobruji.recommendation.infrastructure.RecommendationRequestRepository;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.Mood;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

/**
 * {@link RecommendationService#readById(Long)} 단위 회귀 가드.
 *
 * <p>spec: {@code docs/features/recommendation-algorithm-v1.md} §3 비기능 — read-side 일관성.
 *
 * <p>create-side 경로는 {@code RecommendationServiceBatchPersistTest} /
 * {@code RecommendationServiceDeterminismLogTest} / {@code RecommendationServiceHistoryExcludeSongIdsTest}
 * 로 두텁게 가드되지만 read 경로는 단위 가드가 비어 있었다. 다음 4가지 invariant 를 잠근다.
 *
 * <ol>
 * <li>persisted row 순서를 그대로 결과에 보존 (repo 메서드
 * {@code findByRecommendationRequestIdOrderByRankPositionAsc} 계약을 호출 측이 신뢰)</li>
 * <li>persisted 가 비어 있으면 {@link RecommendationResult} 의 recommendations 도 비어 있다.</li>
 * <li>요청 ID 미존재 시 {@link RecommendationNotFoundException}.</li>
 * <li>persisted row 의 songId 가 {@code Song} 테이블에서 사라진 경우(시드 재구성/곡 비공개)
 * 이슈 #599 결정에 따라 옵션 (a) 필터링 적용 — 누락 entry skip 후 짧은 list 반환.
 * {@link RecommendationService#readHistoryBySessionId(String)} 와 같은 정책. 부분 누락 / 전부 누락
 * 두 경계를 모두 가드한다.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class RecommendationServiceReadByIdTest {

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
    @DisplayName("readById: persisted row 순서를 결과에 그대로 보존 (rank 정렬 가드)")
    void readById_persistedRows_returnsResultInRankOrder() throws Exception {
        // given
        final Long requestId = 100L;
        final RecommendationRequestEntity savedRequest = persistedRequest(requestId);
        given(recommendationRequestRepository.findById(requestId)).willReturn(Optional.of(savedRequest));

        final List<Recommendation> persisted = List.of(
                persistedRecommendation(requestId, 11L, 0.9, "reason-1", 1),
                persistedRecommendation(requestId, 22L, 0.8, "reason-2", 2),
                persistedRecommendation(requestId, 33L, 0.7, "reason-3", 3));
        given(recommendationRepository.findByRecommendationRequestIdOrderByRankPositionAsc(requestId))
                .willReturn(persisted);

        final List<Song> songs = List.of(
                buildSong(11L, "t1"), buildSong(22L, "t2"), buildSong(33L, "t3"));
        given(songRepository.findAllById(List.of(11L, 22L, 33L))).willReturn(songs);

        // when
        final RecommendationResult result = recommendationService.readById(requestId);

        // then
        assertThat(result.requestId()).isEqualTo(requestId);
        assertThat(result.recommendations()).hasSize(3);
        assertThat(result.recommendations())
                .extracting(r -> r.song().getId())
                .containsExactly(11L, 22L, 33L);
        assertThat(result.recommendations())
                .extracting(r -> r.rankPosition())
                .containsExactly(1, 2, 3);
        assertThat(result.recommendations())
                .extracting(r -> r.matchReason())
                .containsExactly("reason-1", "reason-2", "reason-3");
    }

    @Test
    @DisplayName("readById: persisted 가 비면 비어있는 RecommendationResult 반환 (NPE/find 추가호출 없음)")
    void readById_emptyPersisted_returnsEmptyResult() throws Exception {
        // given
        final Long requestId = 101L;
        final RecommendationRequestEntity savedRequest = persistedRequest(requestId);
        given(recommendationRequestRepository.findById(requestId)).willReturn(Optional.of(savedRequest));
        given(recommendationRepository.findByRecommendationRequestIdOrderByRankPositionAsc(requestId))
                .willReturn(List.of());

        // when
        final RecommendationResult result = recommendationService.readById(requestId);

        // then
        assertThat(result.requestId()).isEqualTo(requestId);
        assertThat(result.recommendations()).isEmpty();
    }

    @Test
    @DisplayName("readById: requestId 미존재 → RecommendationNotFoundException (404 매핑 가드)")
    void readById_requestMissing_throwsNotFound() {
        // given
        final Long requestId = 999L;
        given(recommendationRequestRepository.findById(requestId)).willReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> recommendationService.readById(requestId))
                .isInstanceOf(RecommendationNotFoundException.class)
                .hasMessageContaining(String.valueOf(requestId));
    }

    /**
     * 이슈 #599 옵션 (a) 필터링: persisted row 의 songId 가 catalog 에 없으면 해당 entry skip,
     * 살아있는 entry 만 짧은 list 로 반환. 순서는 영속 rank 정렬 그대로 보존.
     */
    @Test
    @DisplayName("readById: Song catalog 부분 누락 시 누락 entry skip 후 short list (rank 보존)")
    void readById_songMissingPartial_returnsShortListSkippingMissing() throws Exception {
        // given
        final Long requestId = 102L;
        final RecommendationRequestEntity savedRequest = persistedRequest(requestId);
        given(recommendationRequestRepository.findById(requestId)).willReturn(Optional.of(savedRequest));

        final List<Recommendation> persisted = List.of(
                persistedRecommendation(requestId, 11L, 0.9, "r1", 1),
                persistedRecommendation(requestId, 22L, 0.8, "r2", 2),
                persistedRecommendation(requestId, 33L, 0.7, "r3", 3));
        given(recommendationRepository.findByRecommendationRequestIdOrderByRankPositionAsc(requestId))
                .willReturn(persisted);

        // 22L 곡이 사라진 catalog (11L, 33L 만 반환)
        given(songRepository.findAllById(List.of(11L, 22L, 33L)))
                .willReturn(List.of(buildSong(11L, "t1"), buildSong(33L, "t3")));

        // when
        final RecommendationResult result = recommendationService.readById(requestId);

        // then — 살아있는 11/33 만 rank 순으로, 22 는 skip.
        assertThat(result.requestId()).isEqualTo(requestId);
        assertThat(result.recommendations()).hasSize(2);
        assertThat(result.recommendations())
                .extracting(r -> r.song().getId())
                .containsExactly(11L, 33L);
        assertThat(result.recommendations())
                .extracting(r -> r.rankPosition())
                .containsExactly(1, 3);
    }

    /**
     * 이슈 #599 (a) 경계: 모든 persisted row 의 song 이 사라졌으면 빈 list 반환 (NPE 없이).
     */
    @Test
    @DisplayName("readById: Song catalog 전부 누락 시 빈 list 반환 (NPE 없음)")
    void readById_songMissingAll_returnsEmptyListWithoutNpe() throws Exception {
        // given
        final Long requestId = 103L;
        final RecommendationRequestEntity savedRequest = persistedRequest(requestId);
        given(recommendationRequestRepository.findById(requestId)).willReturn(Optional.of(savedRequest));

        final List<Recommendation> persisted = List.of(
                persistedRecommendation(requestId, 11L, 0.9, "r1", 1),
                persistedRecommendation(requestId, 22L, 0.8, "r2", 2));
        given(recommendationRepository.findByRecommendationRequestIdOrderByRankPositionAsc(requestId))
                .willReturn(persisted);
        given(songRepository.findAllById(List.of(11L, 22L))).willReturn(List.of());

        // when / then — NPE 없이 빈 list (#599 옵션 (a) 보장)
        assertThatCode(() -> {
            final RecommendationResult result = recommendationService.readById(requestId);
            assertThat(result.requestId()).isEqualTo(requestId);
            assertThat(result.recommendations()).isEmpty();
        }).doesNotThrowAnyException();
    }

    private static RecommendationRequestEntity persistedRequest(final Long id) throws Exception {
        final RecommendationRequestEntity entity = RecommendationRequestEntity.create(
                "session-read", 48, 72, Mood.UPBEAT, null, List.of());
        final Field idField = RecommendationRequestEntity.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(entity, id);
        return entity;
    }

    private static Recommendation persistedRecommendation(
            final Long requestId,
            final Long songId,
            final double score,
            final String matchReason,
            final int rankPosition) throws Exception {
        final Recommendation recommendation = Recommendation.create(
                requestId, songId, score, matchReason, rankPosition);
        // 영속 row 시뮬레이션: id / createdAt 채워둠.
        final Field idField = Recommendation.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(recommendation, (long) rankPosition);
        final Field createdAtField = Recommendation.class.getDeclaredField("createdAt");
        createdAtField.setAccessible(true);
        createdAtField.set(recommendation, LocalDateTime.now());
        return recommendation;
    }

    private static Song buildSong(final Long id, final String title) throws Exception {
        final Song song = Song.builder()
                .title(title).artist("artist-" + id)
                .keyOriginal(MusicalKey.C_MAJOR)
                .mood(Mood.UPBEAT)
                .genre("팝")
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
        final Field idField = Song.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(song, id);
        return song;
    }
}
