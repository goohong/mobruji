package com.mobruji.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
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
 * <li>persisted row 의 songId 가 {@code Song} 테이블에서 사라진 경우, 현 구현은
 * {@code ScoredRecommendation} 생성자 {@code Objects.requireNonNull(song)} 에 부딪혀 NPE.
 * create 경로는 명시 filter 로 막아두지만 read 경로는 없어서 발생 — 본 테스트는
 * <b>현 동작을 명세화</b>하여 후속 fix(필터링 vs 예외 변환) PR 시 의도 변경이 명시적으로
 * 눈에 띄게 한다.</li>
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
     * 현 동작 명세화: persisted row 의 songId 가 {@code Song} 테이블에 없으면 {@link NullPointerException}.
     * {@code ScoredRecommendation} 의 {@code Objects.requireNonNull(song)} 가드에 닿는다.
     * 후속 fix(필터/예외 변환) 가 들어오면 본 테스트가 깨지면서 정책 변경이 명시적으로 드러난다.
     */
    @Test
    @DisplayName("readById: Song catalog 누락 시 현 구현은 NPE (filter 미적용 — 후속 정책 결정 트리거)")
    void readById_songMissing_throwsNullPointer() throws Exception {
        // given
        final Long requestId = 102L;
        final RecommendationRequestEntity savedRequest = persistedRequest(requestId);
        given(recommendationRequestRepository.findById(requestId)).willReturn(Optional.of(savedRequest));

        final List<Recommendation> persisted = List.of(
                persistedRecommendation(requestId, 11L, 0.9, "r1", 1),
                persistedRecommendation(requestId, 22L, 0.8, "r2", 2));
        given(recommendationRepository.findByRecommendationRequestIdOrderByRankPositionAsc(requestId))
                .willReturn(persisted);

        // 22L 곡이 사라진 catalog (11L 만 반환)
        given(songRepository.findAllById(List.of(11L, 22L))).willReturn(List.of(buildSong(11L, "t1")));

        // when / then
        assertThatThrownBy(() -> recommendationService.readById(requestId))
                .isInstanceOf(NullPointerException.class);
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
