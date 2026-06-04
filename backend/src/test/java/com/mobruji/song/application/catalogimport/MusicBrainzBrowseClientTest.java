package com.mobruji.song.application.catalogimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

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
 * {@link MusicBrainzBrowseClient} 단위 테스트 — MockRestServiceServer 로 외부 호출 stub. 결정적 clock/sleeper 로
 * throttle/503 backoff 를 실시간 지연 없이 검증한다.
 *
 * <p>검증 포인트: recording[] → BrowsedRecording 추출(제목/아티스트/ISRC/연도/장르), 부분 누락 graceful,
 * 무매칭/HTTP 오류/타임아웃 → 빈 목록, 503 backoff 후 성공, 503 소진 → 전용 예외, throttle sleep, blank/음수 입력 가드.
 */
class MusicBrainzBrowseClientTest {

    private static CatalogBrowseProperties properties(final int maxRetries) {
        return new CatalogBrowseProperties(
                "https://musicbrainz.org/ws/2",
                "mobruji-backend/0.1 (+test)",
                Duration.ofSeconds(5),
                Duration.ofMillis(1100),
                Duration.ofSeconds(1),
                maxRetries,
                100,
                10,
                50,
                0.3);
    }

    @Test
    @DisplayName("browseByArtist: recordings[] 각각에서 메타 추출")
    void browse_success_extractsRecordings() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        final String body = """
                {
                  "recordings": [
                    {
                      "id": "mbid-1",
                      "title": "좋니",
                      "first-release-date": "2017-06-21",
                      "isrcs": ["KRA401700001"],
                      "tags": [{"name": "ballad", "count": 1}, {"name": "k-pop", "count": 5}],
                      "artist-credit": [{"name": "윤종신"}]
                    },
                    {
                      "id": "mbid-2",
                      "title": "본능적으로",
                      "artist-credit": [{"artist": {"name": "윤종신"}}]
                    }
                  ]
                }
                """;
        server.expect(ExpectedCount.once(),
                MockRestRequestMatchers.requestTo(Matchers.containsString("musicbrainz.org/ws/2/recording")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(MockRestRequestMatchers.queryParam("fmt", "json"))
                .andExpect(MockRestRequestMatchers.queryParam("limit", "100"))
                .andExpect(MockRestRequestMatchers.queryParam("offset", "0"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        final MusicBrainzBrowseClient client = new MusicBrainzBrowseClient(
                properties(3), builder.build(), () -> 0L, millis -> {
                });

        final List<BrowsedRecording> result = client.browseByArtist("윤종신", 100, 0);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).mbId()).isEqualTo("mbid-1");
        assertThat(result.get(0).title()).isEqualTo("좋니");
        assertThat(result.get(0).artist()).isEqualTo("윤종신");
        assertThat(result.get(0).isrc()).isEqualTo("KRA401700001");
        assertThat(result.get(0).releaseYear()).isEqualTo(2017);
        assertThat(result.get(0).genre()).isEqualTo("k-pop");
        // 두 번째: artist-credit[0].artist.name fallback, 나머지 누락 graceful.
        assertThat(result.get(1).artist()).isEqualTo("윤종신");
        assertThat(result.get(1).isrc()).isNull();
        assertThat(result.get(1).releaseYear()).isNull();
        assertThat(result.get(1).genre()).isNull();
        server.verify();
    }

    @Test
    @DisplayName("browseByArtist: recordings 비었으면 빈 목록")
    void browse_emptyRecordings_returnsEmpty() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), MockRestRequestMatchers.requestTo(Matchers.any(String.class)))
                .andRespond(withSuccess("{\"recordings\":[]}", MediaType.APPLICATION_JSON));
        final MusicBrainzBrowseClient client = new MusicBrainzBrowseClient(
                properties(3), builder.build(), () -> 0L, millis -> {
                });

        assertThat(client.browseByArtist("희귀가수", 100, 0)).isEmpty();
    }

    @Test
    @DisplayName("browseByArtist: HTTP 5xx 도 예외 없이 빈 목록")
    void browse_serverError_returnsEmpty() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), MockRestRequestMatchers.requestTo(Matchers.any(String.class)))
                .andRespond(withServerError());
        final MusicBrainzBrowseClient client = new MusicBrainzBrowseClient(
                properties(3), builder.build(), () -> 0L, millis -> {
                });

        assertThat(client.browseByArtist("a", 100, 0)).isEmpty();
    }

    @Test
    @DisplayName("browseByArtist: 타임아웃/IO 실패도 빈 목록")
    void browse_ioException_returnsEmpty() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), MockRestRequestMatchers.requestTo(Matchers.any(String.class)))
                .andRespond(withException(new IOException("connection reset")));
        final MusicBrainzBrowseClient client = new MusicBrainzBrowseClient(
                properties(3), builder.build(), () -> 0L, millis -> {
                });

        assertThat(client.browseByArtist("a", 100, 0)).isEmpty();
    }

    @Test
    @DisplayName("browseByArtist: 503 두 번 후 성공 — backoff 재시도하고 결과 반환")
    void browse_503ThenSuccess_retries() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), MockRestRequestMatchers.requestTo(Matchers.any(String.class)))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(ExpectedCount.once(), MockRestRequestMatchers.requestTo(Matchers.any(String.class)))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(ExpectedCount.once(), MockRestRequestMatchers.requestTo(Matchers.any(String.class)))
                .andRespond(withSuccess("{\"recordings\":[{\"id\":\"mbid\",\"title\":\"t\","
                        + "\"artist-credit\":[{\"name\":\"a\"}]}]}", MediaType.APPLICATION_JSON));
        final AtomicLong sleeps = new AtomicLong();
        final MusicBrainzBrowseClient client = new MusicBrainzBrowseClient(
                properties(3), builder.build(), () -> 0L, millis -> sleeps.incrementAndGet());

        final List<BrowsedRecording> result = client.browseByArtist("a", 100, 0);

        assertThat(result).hasSize(1);
        // backoff sleep 이 최소 2회(503 두 번) 발생.
        assertThat(sleeps.get()).isGreaterThanOrEqualTo(2);
        server.verify();
    }

    @Test
    @DisplayName("browseByArtist: 503 backoff 소진 시 CatalogBrowseRateLimitException")
    void browse_503Exhausted_throws() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), MockRestRequestMatchers.requestTo(Matchers.any(String.class)))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(ExpectedCount.once(), MockRestRequestMatchers.requestTo(Matchers.any(String.class)))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        final MusicBrainzBrowseClient client = new MusicBrainzBrowseClient(
                properties(1), builder.build(), () -> 0L, millis -> {
                });

        assertThatThrownBy(() -> client.browseByArtist("a", 100, 0))
                .isInstanceOf(CatalogBrowseRateLimitException.class);
    }

    @Test
    @DisplayName("browseByArtist: 직전 호출 간격이 throttle 미만이면 sleep")
    void browse_throttle_sleepsWhenTooSoon() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.twice(), MockRestRequestMatchers.requestTo(Matchers.any(String.class)))
                .andRespond(withSuccess("{\"recordings\":[]}", MediaType.APPLICATION_JSON));
        // clock 이 항상 0 → 두 번째 호출은 1100ms throttle 만큼 대기해야 함.
        final AtomicLong sleptMillis = new AtomicLong();
        final MusicBrainzBrowseClient client = new MusicBrainzBrowseClient(
                properties(3), builder.build(), () -> 0L, sleptMillis::addAndGet);

        client.browseByArtist("a", 100, 0);
        client.browseByArtist("a", 100, 0);

        // 두 번째 호출에서 throttle sleep(1100ms) 발생.
        assertThat(sleptMillis.get()).isGreaterThanOrEqualTo(1100);
    }

    @Test
    @DisplayName("browseByArtist: blank artist / 음수 offset / 0 limit 이면 호출 없이 빈 목록")
    void browse_invalidInput_returnsEmptyWithoutCall() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        final MusicBrainzBrowseClient client = new MusicBrainzBrowseClient(
                properties(3), builder.build(), () -> 0L, millis -> {
                });

        assertThat(client.browseByArtist(null, 100, 0)).isEmpty();
        assertThat(client.browseByArtist("  ", 100, 0)).isEmpty();
        assertThat(client.browseByArtist("a", 0, 0)).isEmpty();
        assertThat(client.browseByArtist("a", 100, -1)).isEmpty();
        server.verify();
    }
}
