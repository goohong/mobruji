package com.mobruji.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.mobruji.recommendation.application.RecommendationScorer.Scored;
import com.mobruji.recommendation.application.RecommendationService.ScoredSong;
import com.mobruji.recommendation.domain.RecommendationRequestEntity;
import com.mobruji.recommendation.domain.ScoreBreakdown;
import com.mobruji.recommendation.infrastructure.RecommendationRepository;
import com.mobruji.recommendation.infrastructure.RecommendationRequestRepository;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.Mood;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

/**
 * {@link RecommendationService#create}가 응답 직전 결정성 INFO 로그 1줄을 남기는지 가드한다.
 *
 * <p>spec: {@code docs/features/recommendation-algorithm-v1.md} §3 비기능 (d) 결정성 로그.
 * 형식:
 * <pre>
 * event=recommendation.created request.input.hash=&lt;sha256-hex-16&gt; seed=&lt;long&gt;
 * algoVersion=&lt;v1|v2&gt; resultCount=&lt;n&gt; durationMs=&lt;ms&gt;
 * </pre>
 *
 * <p>회귀 가드 목적:
 * <ul>
 * <li>로그 라인 형식이 깨지면 운영 파서/대시보드가 동시에 깨진다.</li>
 * <li>PII 원문(sessionId/음역대 수치)이 로그에 노출되면 즉시 적발한다 (CLAUDE.md §4 보안).</li>
 * <li>같은 입력 → 같은 input.hash + 같은 seed 결정성을 service 레벨에서도 확인.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class RecommendationServiceDeterminismLogTest {

    private static final String SESSION_ID = "session-abc";
    private static final int VOICE_LOW = 48;
    private static final int VOICE_HIGH = 72;
    private static final Integer PREFERRED_BPM = 120;

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
    @DisplayName("create: 결정성 INFO 로그 1줄을 남긴다 — event/hash/seed/algoVersion/resultCount/durationMs")
    void create_emitsDeterminismLog() throws Exception {
        // given
        stubDerivedFlow(3);

        // when
        recommendationService.create(command(List.of()));

        // then
        final ILoggingEvent logEvent = onlyEventOf("event=recommendation.created");
        assertThat(logEvent.getLevel()).isEqualTo(Level.INFO);

        final String message = logEvent.getFormattedMessage();
        assertThat(message)
                .startsWith("event=recommendation.created ")
                .contains(" request.input.hash=")
                .contains(" seed=")
                .contains(" algoVersion=v2 ")
                .contains(" resultCount=3 ")
                .contains(" durationMs=");

        final String inputHash = extractToken(message, "request.input.hash=");
        // hash 는 SHA-256 의 앞 16 hex 문자 (lowercase).
        assertThat(inputHash).hasSize(16).matches("[0-9a-f]{16}");

        // seed 는 부호 있는 long 토큰이어야 한다.
        final String seedToken = extractToken(message, "seed=");
        assertThat(Long.parseLong(seedToken)).isNotZero();

        // durationMs 는 음수가 아닌 정수.
        final String durationToken = extractToken(message, "durationMs=");
        assertThat(Long.parseLong(durationToken)).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("create: 로그에 sessionId 원문 / 음역대 수치 / 곡 메타가 노출되지 않는다 (PII 가드)")
    void create_logDoesNotLeakPii() throws Exception {
        // given
        stubDerivedFlow(3);

        // when
        recommendationService.create(command(List.of()));

        // then
        final ILoggingEvent logEvent = onlyEventOf("event=recommendation.created");
        final String message = logEvent.getFormattedMessage();
        assertThat(message)
                .doesNotContain(SESSION_ID)
                .doesNotContain(String.valueOf(VOICE_LOW))
                .doesNotContain(String.valueOf(VOICE_HIGH))
                .doesNotContain("s1")
                .doesNotContain("s2")
                .doesNotContain("s3");
    }

    @Test
    @DisplayName("create: 같은 입력 두 번 호출 → 같은 input.hash + 같은 seed (서비스 레벨 결정성)")
    void create_sameInput_sameHashAndSeed() throws Exception {
        // given
        stubDerivedFlow(3);

        // when
        recommendationService.create(command(List.of(10L, 20L)));
        recommendationService.create(command(List.of(20L, 10L))); // 순서 다른 동일 셋

        // then
        final List<ILoggingEvent> events = listAppender.list.stream()
                .filter(e -> e.getFormattedMessage().startsWith("event=recommendation.created"))
                .toList();
        assertThat(events).hasSize(2);

        final String hash1 = extractToken(events.get(0).getFormattedMessage(), "request.input.hash=");
        final String hash2 = extractToken(events.get(1).getFormattedMessage(), "request.input.hash=");
        assertThat(hash1).isEqualTo(hash2);

        final String seed1 = extractToken(events.get(0).getFormattedMessage(), "seed=");
        final String seed2 = extractToken(events.get(1).getFormattedMessage(), "seed=");
        assertThat(seed1).isEqualTo(seed2);
    }

    @Test
    @DisplayName("create: seedStrategy=RANDOM 이면 input.hash 는 '-' (비결정 표기)")
    void create_randomStrategy_hashIsDash() throws Exception {
        // given
        stubCommonFlow(3);
        given(recommendationProperties.seedStrategy()).willReturn(RecommendationProperties.SeedStrategy.RANDOM);

        // when
        recommendationService.create(command(List.of()));

        // then
        final ILoggingEvent logEvent = onlyEventOf("event=recommendation.created");
        assertThat(logEvent.getFormattedMessage()).contains(" request.input.hash=- ");
    }

    private void stubDerivedFlow(final int size) throws Exception {
        stubCommonFlow(size);
        given(recommendationProperties.seedStrategy()).willReturn(RecommendationProperties.SeedStrategy.DERIVED);
    }

    private void stubCommonFlow(final int size) throws Exception {
        final RecommendationRequestEntity savedRequest = persistedRequestEntity();
        given(recommendationRequestRepository.save(any(RecommendationRequestEntity.class))).willReturn(savedRequest);

        final List<Song> catalog = new ArrayList<>();
        for (int i = 1; i <= size; i++) {
            catalog.add(buildSong((long) i, "s" + i, "A" + i));
        }
        given(songRepository.findAll()).willReturn(catalog);

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

    private static CreateRecommendationCommand command(final List<Long> excludeSongIds) {
        return new CreateRecommendationCommand(
                SESSION_ID,
                VOICE_LOW,
                VOICE_HIGH,
                Mood.UPBEAT,
                PREFERRED_BPM, null,
                excludeSongIds, false);
    }

    private ILoggingEvent onlyEventOf(final String prefix) {
        final List<ILoggingEvent> matched = listAppender.list.stream()
                .filter(e -> e.getFormattedMessage().startsWith(prefix))
                .toList();
        assertThat(matched).as("log lines starting with: %s", prefix).hasSize(1);
        return matched.get(0);
    }

    /**
     * 로그 라인에서 {@code key=value} 형식의 value 토큰을 추출한다.
     * value 는 공백 전까지. 라인 끝이면 전체.
     */
    private static String extractToken(final String message, final String key) {
        final int idx = message.indexOf(key);
        assertThat(idx).as("key %s present in: %s", key, message).isNotNegative();
        final int start = idx + key.length();
        final int space = message.indexOf(' ', start);
        return space < 0 ? message.substring(start) : message.substring(start, space);
    }

    private static RecommendationRequestEntity persistedRequestEntity() throws Exception {
        final RecommendationRequestEntity entity = RecommendationRequestEntity.create(
                SESSION_ID, VOICE_LOW, VOICE_HIGH, Mood.UPBEAT, PREFERRED_BPM, null, List.of());
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
