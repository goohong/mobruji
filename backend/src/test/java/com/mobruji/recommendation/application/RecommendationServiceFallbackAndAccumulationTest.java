package com.mobruji.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.mobruji.recommendation.application.RecommendationScorer.Scored;
import com.mobruji.recommendation.application.RecommendationService.ScoredSong;
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
 * {@link RecommendationService#create} fallback(mood=null) / excludeSongIds 누적 분기 service-레벨 가드.
 *
 * <p>커버 갭: SeedDeriverTest / RecommendationServiceDeterminismLogTest 는 deriver 단위 또는 mood=UPBEAT
 * 경로만 다뤄, mood=null 이 create 전체 흐름(요청 저장 → 점수 → 다양성 → saveAll → 결정성 로그)을 NPE 없이
 * 통과하는지와, 같은 voiceRange/mood 인데 제외 셋만 달라지면 seed 도 달라지는 회귀("다시 버튼이 같은 결과"
 * 회귀 — rev 사이클 3) 가 service 통합 단위에서는 잠겨있지 않았다.
 *
 * <p>spec: {@code docs/features/recommendation-algorithm-v1.md} §3 — mood 옵션, 결정성, 제외 곡.
 */
@ExtendWith(MockitoExtension.class)
class RecommendationServiceFallbackAndAccumulationTest {

    private static final String SESSION_ID = "session-fallback";
    private static final int VOICE_LOW = 48;
    private static final int VOICE_HIGH = 72;

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

    private Logger serviceLogger;
    private ListAppender<ILoggingEvent> listAppender;

    @BeforeEach
    void attachAppender() {
        serviceLogger = (Logger) LoggerFactory.getLogger(RecommendationService.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        serviceLogger.addAppender(listAppender);
    }

    @AfterEach
    void detachAppender() {
        serviceLogger.detachAppender(listAppender);
    }

    @Test
    @DisplayName("create: mood=null fallback — 결과 정상 반환 + NPE 없음")
    void create_moodNullFallback_returnsResult() throws Exception {
        stubFlow(3, /* mood */ null, List.of());
        final RecommendationResult result = recommendationService.create(command(null, List.of()));
        assertThat(result.recommendations()).hasSize(3);
    }

    @Test
    @DisplayName("create: mood=null fallback — scorer 호출에도 mood 인자가 null 로 전파된다")
    void create_moodNullFallback_propagatesNullMoodToScorer() throws Exception {
        stubFlow(3, /* mood */ null, List.of());
        recommendationService.create(command(null, List.of()));
        verify(recommendationScorer, atLeastOnce())
                .score(any(Song.class), anyInt(), anyInt(), isNull(), any(), any(), any());
    }

    @Test
    @DisplayName("create: mood=null fallback — 결정성 로그 hash 가 정상 16-hex (RANDOM '-' 아님) + algoVersion=v2")
    void create_moodNullFallback_emitsValidDeterministicHash() throws Exception {
        stubFlow(3, /* mood */ null, List.of());
        recommendationService.create(command(null, List.of()));

        final List<ILoggingEvent> events = createdLogs();
        assertThat(events).hasSize(1);
        final String message = events.get(0).getFormattedMessage();
        assertThat(extractToken(message, "request.input.hash=")).hasSize(16).matches("[0-9a-f]{16}");
        assertThat(message).contains(" algoVersion=v2 ");
    }

    @Test
    @DisplayName("create: excludeSongIds 누적 — 다른 제외 셋이면 seed/hash 가 달라진다 (재추천 회귀 가드)")
    void create_excludeSongIds_accumulation_producesDifferentSeed() throws Exception {
        // save 가 반환하는 entity 가 호출별로 달라야 SeedDeriver 입력이 분기된다.
        stubCommonFlow(3);
        given(recommendationProperties.seedStrategy())
                .willReturn(RecommendationProperties.SeedStrategy.DERIVED);
        given(recommendationRequestRepository.save(any(RecommendationRequestEntity.class)))
                .willReturn(persistedRequestEntity(Mood.UPBEAT, List.of(10L)))
                .willReturn(persistedRequestEntity(Mood.UPBEAT, List.of(10L, 20L)));

        recommendationService.create(command(Mood.UPBEAT, List.of(10L)));
        recommendationService.create(command(Mood.UPBEAT, List.of(10L, 20L)));

        final List<ILoggingEvent> events = createdLogs();
        assertThat(events).hasSize(2);
        assertThat(extractToken(events.get(0).getFormattedMessage(), "seed="))
                .isNotEqualTo(extractToken(events.get(1).getFormattedMessage(), "seed="));
        assertThat(extractToken(events.get(0).getFormattedMessage(), "request.input.hash="))
                .isNotEqualTo(extractToken(events.get(1).getFormattedMessage(), "request.input.hash="));
    }

    @Test
    @DisplayName("create: excludeSongIds 의 곡은 scorer 호출 대상에서 제외된다 (점수 계산 전 필터)")
    void create_excludeSongIds_filtersOutBeforeScoring() throws Exception {
        stubCommonFlow(3);
        given(recommendationProperties.seedStrategy())
                .willReturn(RecommendationProperties.SeedStrategy.DERIVED);
        given(recommendationRequestRepository.save(any(RecommendationRequestEntity.class)))
                .willReturn(persistedRequestEntity(Mood.UPBEAT, List.of(2L)));

        recommendationService.create(command(Mood.UPBEAT, List.of(2L)));

        final ArgumentCaptor<Song> songCaptor = ArgumentCaptor.forClass(Song.class);
        verify(recommendationScorer, atLeastOnce())
                .score(songCaptor.capture(), anyInt(), anyInt(), any(), any(), any(), any());
        assertThat(songCaptor.getAllValues()).extracting(Song::getId)
                .doesNotContain(2L)
                .containsExactlyInAnyOrder(1L, 3L);
    }

    private void stubFlow(final int size, final Mood mood, final List<Long> excludeSongIds) throws Exception {
        stubCommonFlow(size);
        given(recommendationProperties.seedStrategy())
                .willReturn(RecommendationProperties.SeedStrategy.DERIVED);
        given(recommendationRequestRepository.save(any(RecommendationRequestEntity.class)))
                .willReturn(persistedRequestEntity(mood, excludeSongIds));
    }

    private void stubCommonFlow(final int size) throws Exception {
        final List<Song> catalog = new ArrayList<>();
        for (int i = 1; i <= size; i++) {
            catalog.add(buildSong((long) i, "s" + i, "A" + i));
        }
        given(songRepository.findAllWithVocalRange()).willReturn(catalog);

        final ScoreBreakdown breakdown = new ScoreBreakdown(1.0, 1.0, 0.0, 0.0, 1.0, 0.5, 0.0);
        given(recommendationScorer.score(any(Song.class), anyInt(), anyInt(), any(), any(), any(), any()))
                .willReturn(new Scored(0.9, breakdown));

        final List<ScoredSong> diversified = new ArrayList<>();
        for (final Song song : catalog) {
            diversified.add(new ScoredSong(song, new Scored(0.9, breakdown)));
        }
        given(diversityPostProcessor.apply(anyList(), anyInt())).willReturn(diversified);
        given(recommendationProperties.resultCount()).willReturn(size);
    }

    private List<ILoggingEvent> createdLogs() {
        return listAppender.list.stream()
                .filter(e -> e.getFormattedMessage().startsWith("event=recommendation.created"))
                .toList();
    }

    private static CreateRecommendationCommand command(final Mood mood, final List<Long> excludeSongIds) {
        return new CreateRecommendationCommand(
                SESSION_ID, VOICE_LOW, VOICE_HIGH, mood, /* preferredBpm */ null, null, excludeSongIds, false);
    }

    private static String extractToken(final String message, final String key) {
        final int idx = message.indexOf(key);
        assertThat(idx).as("key %s present in: %s", key, message).isNotNegative();
        final int start = idx + key.length();
        final int space = message.indexOf(' ', start);
        return space < 0 ? message.substring(start) : message.substring(start, space);
    }

    private static RecommendationRequestEntity persistedRequestEntity(
            final Mood mood, final List<Long> excludeSongIds) throws Exception {
        final RecommendationRequestEntity entity = RecommendationRequestEntity.create(
                SESSION_ID, VOICE_LOW, VOICE_HIGH, mood, /* preferredBpm */ null, null, excludeSongIds);
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
