package com.mobruji.song.application.musicbrainz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.web.client.RestClient;

import org.hamcrest.Matchers;

/**
 * {@link MusicBrainzClient} 단위 테스트 — MockRestServiceServer 로 외부 호출 stub, 주입 clock/sleeper 로
 * throttle/backoff 를 실시간 지연 없이 검증한다.
 */
class MusicBrainzClientTest {

    private static final LongSupplier FIXED_CLOCK = () -> 0L;

    private static MusicBrainzProperties props(
            final Duration throttle, final int maxRetries, final Duration backoff) {
        return new MusicBrainzProperties(
                "https://musicbrainz.org/ws/2",
                "mobruji-backend/0.1 (+test)",
                Duration.ofSeconds(5),
                throttle,
                maxRetries,
                backoff,
                90,
                5,
                new MusicBrainzProperties.Backfill(30, false));
    }

    private static MusicBrainzProperties defaultProps() {
        return props(Duration.ZERO, 3, Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("searchTopRecording: top-hit 에서 mbId/isrc/score 추출")
    void search_success_extractsTopHit() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        final String body = """
                {
                  "recordings": [
                    {"id": "mbid-1", "score": 95, "isrcs": ["KRA401700001"]},
                    {"id": "mbid-2", "score": 40}
                  ]
                }
                """;
        server.expect(ExpectedCount.once(),
                MockRestRequestMatchers.requestTo(Matchers.containsString("musicbrainz.org/ws/2/recording")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(MockRestRequestMatchers.queryParam("fmt", "json"))
                .andExpect(MockRestRequestMatchers.queryParam("limit", "5"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        final MusicBrainzClient client = new MusicBrainzClient(
                defaultProps(), builder.build(), FIXED_CLOCK, millis -> {
                });

        final Optional<MusicBrainzMatch> match = client.searchTopRecording("좋니", "윤종신");

        assertThat(match).isPresent();
        assertThat(match.get().mbId()).isEqualTo("mbid-1");
        assertThat(match.get().isrc()).isEqualTo("KRA401700001");
        assertThat(match.get().score()).isEqualTo(95);
        assertThat(match.get().confidence()).isEqualTo(0.95);
        server.verify();
    }

    @Test
    @DisplayName("searchTopRecording: 저score top-hit 도 그대로 반환 (임계 판단은 호출 측)")
    void search_lowScore_returnsTopHitForCaller() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), MockRestRequestMatchers.requestTo(Matchers.any(String.class)))
                .andRespond(withSuccess("{\"recordings\":[{\"id\":\"mbid-x\",\"score\":50}]}",
                        MediaType.APPLICATION_JSON));
        final MusicBrainzClient client = new MusicBrainzClient(
                defaultProps(), builder.build(), FIXED_CLOCK, millis -> {
                });

        final Optional<MusicBrainzMatch> match = client.searchTopRecording("t", "a");

        assertThat(match).isPresent();
        assertThat(match.get().score()).isEqualTo(50);
        assertThat(match.get().isrc()).isNull();
    }

    @Test
    @DisplayName("searchTopRecording: recordings 비었으면 empty")
    void search_emptyRecordings_returnsEmpty() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), MockRestRequestMatchers.requestTo(Matchers.any(String.class)))
                .andRespond(withSuccess("{\"recordings\":[]}", MediaType.APPLICATION_JSON));
        final MusicBrainzClient client = new MusicBrainzClient(
                defaultProps(), builder.build(), FIXED_CLOCK, millis -> {
                });

        assertThat(client.searchTopRecording("t", "a")).isEmpty();
    }

    @Test
    @DisplayName("searchTopRecording: title/artist blank 이면 호출 없이 empty")
    void search_blankInput_returnsEmptyWithoutCall() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        final MusicBrainzClient client = new MusicBrainzClient(
                defaultProps(), builder.build(), FIXED_CLOCK, millis -> {
                });

        assertThat(client.searchTopRecording(null, "a")).isEmpty();
        assertThat(client.searchTopRecording("t", " ")).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("searchTopRecording: 비-503 5xx / timeout 은 예외 전파 없이 empty (곡 단위 graceful skip)")
    void search_serverErrorAndTimeout_returnEmpty() {
        final RestClient.Builder errorBuilder = RestClient.builder();
        final MockRestServiceServer errorServer = MockRestServiceServer.bindTo(errorBuilder).build();
        errorServer.expect(ExpectedCount.once(), MockRestRequestMatchers.requestTo(Matchers.any(String.class)))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        final MusicBrainzClient errorClient = new MusicBrainzClient(
                defaultProps(), errorBuilder.build(), FIXED_CLOCK, millis -> {
                });
        assertThat(errorClient.searchTopRecording("t", "a")).isEmpty();

        final RestClient.Builder timeoutBuilder = RestClient.builder();
        final MockRestServiceServer timeoutServer = MockRestServiceServer.bindTo(timeoutBuilder).build();
        timeoutServer.expect(ExpectedCount.once(), MockRestRequestMatchers.requestTo(Matchers.any(String.class)))
                .andRespond(withException(new IOException("connection reset")));
        final MusicBrainzClient timeoutClient = new MusicBrainzClient(
                defaultProps(), timeoutBuilder.build(), FIXED_CLOCK, millis -> {
                });
        assertThat(timeoutClient.searchTopRecording("t", "a")).isEmpty();
    }

    @Test
    @DisplayName("searchTopRecording: 503 → backoff 재시도 후 200 성공, backoff=base×2^0")
    void search_503ThenSuccess_retriesWithBackoff() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), MockRestRequestMatchers.requestTo(Matchers.any(String.class)))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(ExpectedCount.once(), MockRestRequestMatchers.requestTo(Matchers.any(String.class)))
                .andRespond(withSuccess("{\"recordings\":[{\"id\":\"mbid-ok\",\"score\":99}]}",
                        MediaType.APPLICATION_JSON));
        final List<Long> sleeps = new ArrayList<>();
        final MusicBrainzClient client = new MusicBrainzClient(
                props(Duration.ZERO, 3, Duration.ofSeconds(1)), builder.build(), FIXED_CLOCK, sleeps::add);

        final Optional<MusicBrainzMatch> match = client.searchTopRecording("t", "a");

        assertThat(match).isPresent();
        assertThat(match.get().mbId()).isEqualTo("mbid-ok");
        assertThat(sleeps).containsExactly(1000L);
        server.verify();
    }

    @Test
    @DisplayName("searchTopRecording: 503 이 maxRetries 소진 → MusicBrainzRateLimitException (batch 중단 신호)")
    void search_503Exhausted_throwsRateLimit() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        for (int i = 0; i < 3; i++) {
            server.expect(ExpectedCount.once(), MockRestRequestMatchers.requestTo(Matchers.any(String.class)))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        }
        final List<Long> sleeps = new ArrayList<>();
        final MusicBrainzClient client = new MusicBrainzClient(
                props(Duration.ZERO, 2, Duration.ofSeconds(1)), builder.build(), FIXED_CLOCK, sleeps::add);

        assertThatThrownBy(() -> client.searchTopRecording("t", "a"))
                .isInstanceOf(MusicBrainzRateLimitException.class);
        // base×2^0, base×2^1 두 번 backoff 후 3번째 503 에서 소진.
        assertThat(sleeps).containsExactly(1000L, 2000L);
        server.verify();
    }

    @Test
    @DisplayName("throttle: 직전 호출과 간격이 throttle 미만이면 그 차이만큼 sleep (1 req/s 강제)")
    void throttle_enforcesMinimumInterval() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        for (int i = 0; i < 2; i++) {
            server.expect(ExpectedCount.once(), MockRestRequestMatchers.requestTo(Matchers.any(String.class)))
                    .andRespond(withSuccess("{\"recordings\":[{\"id\":\"m\",\"score\":99}]}",
                            MediaType.APPLICATION_JSON));
        }
        // 1번째 호출 시각 10_000, 2번째 호출 시각 10_300 (300ms 뒤) → interval 1100 미달분 800 sleep.
        final AtomicInteger tick = new AtomicInteger();
        final long[] clockValues = {10_000L, 10_300L};
        final LongSupplier clock = () -> clockValues[tick.getAndIncrement()];
        final List<Long> sleeps = new ArrayList<>();
        final MusicBrainzClient client = new MusicBrainzClient(
                props(Duration.ofMillis(1100), 3, Duration.ofSeconds(1)), builder.build(), clock, sleeps::add);

        client.searchTopRecording("t1", "a1");
        client.searchTopRecording("t2", "a2");

        // 1번째는 직전 호출 없어 sleep 0(미기록), 2번째만 800ms sleep.
        assertThat(sleeps).containsExactly(800L);
        server.verify();
    }

    @Test
    @DisplayName("lookupIsrc: 상세 응답 top-level isrcs[0] 반환, 무 ISRC 면 empty")
    void lookupIsrc_extractsFirst() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(),
                MockRestRequestMatchers.requestTo(Matchers.containsString("/recording/mbid-1")))
                .andExpect(MockRestRequestMatchers.queryParam("inc", "isrcs"))
                .andRespond(withSuccess("{\"isrcs\":[\"KRB123\"]}", MediaType.APPLICATION_JSON));
        final MusicBrainzClient client = new MusicBrainzClient(
                defaultProps(), builder.build(), FIXED_CLOCK, millis -> {
                });

        assertThat(client.lookupIsrc("mbid-1")).contains("KRB123");
    }
}
