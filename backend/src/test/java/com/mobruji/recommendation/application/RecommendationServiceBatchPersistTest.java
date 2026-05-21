package com.mobruji.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mobruji.recommendation.application.RecommendationScorer.Scored;
import com.mobruji.recommendation.application.RecommendationService.ScoredSong;
import com.mobruji.recommendation.domain.Recommendation;
import com.mobruji.recommendation.domain.RecommendationRequestEntity;
import com.mobruji.recommendation.domain.RecommendationResult;
import com.mobruji.recommendation.domain.ScoreBreakdown;
import com.mobruji.recommendation.infrastructure.RecommendationRepository;
import com.mobruji.recommendation.infrastructure.RecommendationRequestRepository;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.Mood;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

/**
 * RecommendationService.create 가 결과 row를 개별 save 가 아닌
 * {@code saveAll} 1회로 모아 영속하는지 가드한다.
 *
 * <p>배경: rev 사이클 11 k6 첫 실행 p95=279.71ms 회귀 발생.
 * 기존 구현은 결과 size만큼 {@code recommendationRepository.save(...)} 를 반복 호출했고,
 * 각 호출이 EntityManager flush/dirty-check 비용을 매번 유발해 부하 측정 지표를 악화시켰다.
 * 회귀 fix 후 다시 N회 save 호출 형태로 돌아가지 않도록 단위 회귀 가드로 잠근다.
 * spec: docs/features/recommendation-algorithm-v1.md §3 비기능 — p95 200ms.
 */
@ExtendWith(MockitoExtension.class)
class RecommendationServiceBatchPersistTest {

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
    @DisplayName("create: 결과 row는 saveAll 1회로 영속한다 (개별 save 금지) — k6 p95 회귀 가드")
    void create_persistsResultsViaSaveAllOnce() throws Exception {
        // given
        final RecommendationRequestEntity savedRequest = persistedRequestEntity();
        given(recommendationRequestRepository.save(any(RecommendationRequestEntity.class))).willReturn(savedRequest);

        final List<Song> catalog = List.of(
                buildSong(1L, "s1", "A1"),
                buildSong(2L, "s2", "A2"),
                buildSong(3L, "s3", "A3"));
        given(songRepository.findAll()).willReturn(catalog);

        final ScoreBreakdown breakdown = new ScoreBreakdown(1.0, 1.0, 0.0, 0.0, 1.0, 0.5);
        given(recommendationScorer.score(any(Song.class), anyInt(), anyInt(), any(), any(), any()))
                .willReturn(new Scored(0.9, breakdown));

        final List<ScoredSong> diversified = new ArrayList<>();
        for (final Song song : catalog) {
            diversified.add(new ScoredSong(song, new Scored(0.9, breakdown)));
        }
        given(diversityPostProcessor.apply(anyList(), anyInt())).willReturn(diversified);

        given(recommendationProperties.resultCount()).willReturn(3);
        given(recommendationProperties.seedStrategy()).willReturn(RecommendationProperties.SeedStrategy.DERIVED);

        final CreateRecommendationCommand command = new CreateRecommendationCommand(
                "session-abc",
                48,
                72,
                Mood.UPBEAT,
                null,
                List.of());

        // when
        final RecommendationResult result = recommendationService.create(command);

        // then: saveAll 1회, save(Recommendation) 0회
        @SuppressWarnings("unchecked") final ArgumentCaptor<Iterable<Recommendation>> captor = ArgumentCaptor.forClass(
                Iterable.class);
        verify(recommendationRepository, times(1)).saveAll(captor.capture());
        verify(recommendationRepository, never()).save(any(Recommendation.class));

        final List<Recommendation> persisted = new ArrayList<>();
        captor.getValue().forEach(persisted::add);
        assertThat(persisted).hasSize(3);
        assertThat(persisted).extracting(Recommendation::getRankPosition).containsExactly(1, 2, 3);
        assertThat(result.recommendations()).hasSize(3);
    }

    private static RecommendationRequestEntity persistedRequestEntity() throws Exception {
        final RecommendationRequestEntity entity = RecommendationRequestEntity.create(
                "session-abc", 48, 72, Mood.UPBEAT, null, List.of());
        // mock save 후 id 가 비어 있으면 Recommendation.create 가 NPE — 리플렉션으로 id 주입.
        final Field idField = RecommendationRequestEntity.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(entity, 100L);
        return entity;
    }

    private static Song buildSong(final Long id, final String title, final String artist) throws Exception {
        final Song song = Song.builder()
                .title(title).artist(artist)
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
