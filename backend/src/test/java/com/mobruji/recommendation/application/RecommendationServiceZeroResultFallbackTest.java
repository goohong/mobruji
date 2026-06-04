package com.mobruji.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mobruji.recommendation.application.RecommendationScorer.Scored;
import com.mobruji.recommendation.application.RecommendationService.ScoredSong;
import com.mobruji.recommendation.domain.FilterRelaxation;
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
 * {@link RecommendationService#create} 결과 0건(빈 화면) fallback(#1668) service-레벨 가드.
 *
 * <p>추천 파이프라인의 유일한 하드 필터는 {@code excludeSongIds} 다 — 음역대·분위기·연령대는 점수 신호일 뿐 후보를
 * 0건으로 줄이지 않는다. fallback 은 클라이언트가 제외 곡을 명시하지 않은 빈 exclude(=재추천 버튼) 호출에만 적용된다 —
 * 클라이언트가 제외 곡을 누적해 보내는 페이지네이션(#1835)은 풀 소진 시 fallback 없이 빈 결과로 종료한다. 이 테스트는
 * (1) 빈 exclude + 세션 누적(#1549)만 풀어 채우는 1단계, (2) 클라이언트 명시 제외 페이지네이션의 풀 소진 종료(#1835),
 * (3) 정상 매칭은 완화 표기가 없는지(회귀 가드)를 검증한다.
 *
 * <p>spec: 이슈 #1668 — 빈 exclude 결과 0건이면 단계적 필터 완화로 최소 결과 보장 + {@code relaxed} 플래그.
 */
@ExtendWith(MockitoExtension.class)
class RecommendationServiceZeroResultFallbackTest {

    private static final String SESSION_ID = "session-zero-fallback";
    private static final int VOICE_LOW = 48;
    private static final int VOICE_HIGH = 72;
    private static final int RESULT_COUNT = 10;

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
    @DisplayName("create: 클라이언트가 제외 곡을 명시(페이지네이션)하고 풀이 소진되면 fallback 없이 빈 결과로 종료한다 (#1835)")
    void create_clientExcludesPoolExhausted_terminatesEmpty() throws Exception {
        // given: 카탈로그 3곡 전부를 클라이언트가 명시 제외(무한스크롤로 전부 본 상황) → 정상 후보는 0건
        stubScoringPipeline(3);
        given(recommendationProperties.seedStrategy())
                .willReturn(RecommendationProperties.SeedStrategy.DERIVED);
        given(recommendationRequestRepository.save(any(RecommendationRequestEntity.class)))
                .willReturn(persistedRequestEntity(Mood.UPBEAT, List.of(1L, 2L, 3L)));

        // when
        final RecommendationResult result = recommendationService.create(
                command(Mood.UPBEAT, List.of(1L, 2L, 3L), /* excludeSessionHistory */ false));

        // then: 0건 fallback 없이 빈 결과로 종료해 이미 본 곡을 재노출(재surface)하지 않는다
        assertThat(result.recommendations()).isEmpty();
        assertThat(result.relaxed()).isFalse();
        assertThat(result.relaxedFilters()).isEmpty();
    }

    @Test
    @DisplayName("create: 세션 누적 제외(#1549)로 0건이면 1단계 완화(SESSION_HISTORY)로 채우고 명시 제외는 유지한다")
    void create_sessionHistoryExhausts_relaxesSessionHistoryOnly() throws Exception {
        // given: 클라이언트 명시 제외는 없지만, 세션 누적 제외가 3곡(전부)을 덮는다
        stubScoringPipeline(3);
        given(recommendationProperties.seedStrategy())
                .willReturn(RecommendationProperties.SeedStrategy.DERIVED);
        given(recommendationRequestRepository.save(any(RecommendationRequestEntity.class)))
                .willReturn(persistedRequestEntity(Mood.UPBEAT, List.of(1L, 2L, 3L)));
        given(recommendationRepository.findDistinctRecommendedSongIdsBySessionId(SESSION_ID))
                .willReturn(List.of(1L, 2L, 3L));
        given(recommendationRequestRepository.findDistinctExcludeSongIdsBySessionId(SESSION_ID))
                .willReturn(List.of());

        // when
        final RecommendationResult result = recommendationService.create(
                command(Mood.UPBEAT, List.of(), /* excludeSessionHistory */ true));

        // then: 세션 누적만 풀어 결과를 채우고 1단계 완화로 표기 (명시 제외 곡이 없으므로 EXCLUDED_SONGS 까지 가지 않음)
        assertThat(result.recommendations()).isNotEmpty();
        assertThat(result.relaxed()).isTrue();
        assertThat(result.relaxedFilters()).containsExactly(FilterRelaxation.SESSION_HISTORY);
    }

    @Test
    @DisplayName("create: 정상 매칭(0건 아님)은 완화 표기가 없다 — relaxed:false 회귀 가드")
    void create_normalMatch_isNotRelaxed() throws Exception {
        // given: 제외 없음 → 정상 후보 3곡
        stubScoringPipeline(3);
        given(recommendationProperties.seedStrategy())
                .willReturn(RecommendationProperties.SeedStrategy.DERIVED);
        given(recommendationRequestRepository.save(any(RecommendationRequestEntity.class)))
                .willReturn(persistedRequestEntity(Mood.UPBEAT, List.of()));

        // when
        final RecommendationResult result = recommendationService.create(
                command(Mood.UPBEAT, List.of(), /* excludeSessionHistory */ false));

        // then
        assertThat(result.recommendations()).hasSize(3);
        assertThat(result.relaxed()).isFalse();
        assertThat(result.relaxedFilters()).isEmpty();
    }

    /**
     * scorer 는 고정 점수, diversity 는 입력 후보를 점수 순 그대로 resultCount 까지 통과시키는 현실적 stub.
     * 결과 0건 여부는 오직 후보 필터링(findAll 에서 제외 곡 차감)으로 결정돼, fallback 분기를 service 통합 단위로 검증한다.
     */
    private void stubScoringPipeline(final int catalogSize) throws Exception {
        final List<Song> catalog = new ArrayList<>();
        for (int i = 1; i <= catalogSize; i++) {
            catalog.add(buildSong((long) i, "s" + i, "A" + i));
        }
        given(songRepository.findAllWithVocalRange()).willReturn(catalog);
        given(recommendationProperties.resultCount()).willReturn(RESULT_COUNT);

        final ScoreBreakdown breakdown = new ScoreBreakdown(1.0, 1.0, 0.0, 0.0, 1.0, 0.5, 0.0, 0.0);
        // 풀 소진 종료(#1835) 케이스는 후보가 0건이라 scorer 까지 도달하지 않으므로 lenient — fallback 경로 테스트만 점수를 쓴다.
        lenient().when(recommendationScorer.score(any(Song.class), anyInt(), anyInt(), any(), any(), any(), any(),
                any()))
                .thenReturn(new Scored(0.9, breakdown));
        given(diversityPostProcessor.apply(anyList(), anyInt())).willAnswer(invocation -> {
            final List<ScoredSong> candidates = invocation.getArgument(0);
            final int resultCount = invocation.getArgument(1);
            return candidates.stream().limit(resultCount).toList();
        });
    }

    private static CreateRecommendationCommand command(
            final Mood mood, final List<Long> excludeSongIds, final boolean excludeSessionHistory) {
        return new CreateRecommendationCommand(
                SESSION_ID, VOICE_LOW, VOICE_HIGH, mood, /* preferredBpm */ null, null, null,
                excludeSongIds, excludeSessionHistory);
    }

    private static RecommendationRequestEntity persistedRequestEntity(
            final Mood mood, final List<Long> excludeSongIds) throws Exception {
        final RecommendationRequestEntity entity = RecommendationRequestEntity.create(
                SESSION_ID, VOICE_LOW, VOICE_HIGH, mood, /* preferredBpm */ null, null, null, excludeSongIds);
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
