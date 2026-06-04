package com.mobruji.song.application.musicbrainz;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * MusicBrainz Recording REST 어댑터 — spec {@code musicbrainz-integration.md} §5-3.
 *
 * <ul>
 * <li>{@link #searchTopRecording(String, String)} — {@code GET /recording?query=...&fmt=json&limit=N} 검색,
 * MusicBrainz 가 score 내림차순 정렬한 top-hit 을 {@link MusicBrainzMatch}(mbId/isrc/score) 로 반환.</li>
 * <li>{@link #lookupIsrc(String)} — {@code GET /recording/{mbid}?inc=isrcs&fmt=json} 상세 조회로 검색 응답에
 * ISRC 가 없을 때 보강.</li>
 * </ul>
 *
 * <p>약관 준수 — 모든 호출은 contact 포함 식별 {@code User-Agent} 헤더({@code properties.userAgent})를 부착하고,
 * **호출 직전 per-instance throttle** 로 1 req/s rate limit 을 강제한다. 503 응답은 지수 backoff
 * (base × 2^attempt, {@code maxRetries} 회)로 재시도하고, 소진 시 {@link MusicBrainzRateLimitException} 을 던져
 * batch 전체 중단을 신호한다.
 *
 * <p>graceful — 503 소진을 제외한 외부 호출/parse 실패(4xx/기타 5xx/timeout/무매칭)는 {@link Optional#empty()}
 * 로 반환하고 예외를 던지지 않는다. 로깅은 검색어 원문 대신 host/status/elapsedMs 위주 (CLAUDE.md §4 보안).
 */
@Component
public class MusicBrainzClient {

    private static final Logger LOG = LoggerFactory.getLogger(MusicBrainzClient.class);
    private static final int HTTP_SERVICE_UNAVAILABLE = 503;

    private final MusicBrainzProperties properties;
    private final RestClient restClient;
    private final LongSupplier clockMillis;
    private final Sleeper sleeper;
    private final AtomicLong lastRequestAtMillis = new AtomicLong(0L);

    @Autowired
    public MusicBrainzClient(final MusicBrainzProperties properties) {
        this(properties, defaultRestClient(properties), System::currentTimeMillis, Thread::sleep);
    }

    /**
     * 테스트 주입용 — stub server 의 RestClient + 결정적 clock/sleeper 를 넣어 throttle/backoff 를 실시간 지연
     * 없이 검증한다.
     */
    MusicBrainzClient(
            final MusicBrainzProperties properties,
            final RestClient restClient,
            final LongSupplier clockMillis,
            final Sleeper sleeper) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.restClient = Objects.requireNonNull(restClient, "restClient must not be null");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis must not be null");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper must not be null");
    }

    /**
     * (title, artist) 로 recording 을 검색해 top-hit 매칭을 반환한다. score 임계 판단은 호출 측
     * ({@link MusicBrainzBackfillCommand}) 책임 — 본 메서드는 top-hit 을 그대로 돌려준다.
     *
     * @return top-hit 매칭. 무매칭/4xx/기타 5xx/timeout/null body 면 {@link Optional#empty()}.
     * @throws MusicBrainzRateLimitException 503 이 backoff 재시도를 모두 소진한 경우
     */
    public Optional<MusicBrainzMatch> searchTopRecording(final String title, final String artist) {
        if (title == null || title.isBlank() || artist == null || artist.isBlank()) {
            return Optional.empty();
        }
        final URI uri = buildSearchUri(title, artist);
        final JsonNode body = getJsonGraceful(uri);
        if (body == null) {
            return Optional.empty();
        }
        final JsonNode recordings = body.path("recordings");
        if (!recordings.isArray() || recordings.isEmpty()) {
            LOG.info("musicbrainz search miss host={}", uri.getHost());
            return Optional.empty();
        }
        return Optional.of(toMatch(recordings.get(0)));
    }

    /**
     * recording 상세 조회로 첫 ISRC 를 가져온다 — 검색 응답에 ISRC 가 없을 때 보강.
     *
     * @return 첫 ISRC. 무 ISRC/오류면 {@link Optional#empty()}.
     * @throws MusicBrainzRateLimitException 503 이 backoff 재시도를 모두 소진한 경우
     */
    public Optional<String> lookupIsrc(final String mbId) {
        if (mbId == null || mbId.isBlank()) {
            return Optional.empty();
        }
        final URI uri = buildLookupUri(mbId);
        final JsonNode body = getJsonGraceful(uri);
        if (body == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(firstIsrc(body));
    }

    /**
     * throttle + 503 backoff 를 적용해 JSON 을 가져온다. 503 소진은 {@link MusicBrainzRateLimitException} 으로
     * 전파하고, 그 외 모든 실패는 {@code null} 로 graceful 흡수한다.
     */
    private JsonNode getJsonGraceful(final URI uri) {
        try {
            return getWithRetry(uri);
        } catch (final MusicBrainzRateLimitException e) {
            throw e;
        } catch (final ResourceAccessException e) {
            LOG.warn("musicbrainz timeout host={} reason={}", uri.getHost(), e.getMessage());
            return null;
        } catch (final RestClientException e) {
            LOG.warn("musicbrainz http error host={} reason={}", uri.getHost(), e.getMessage());
            return null;
        } catch (final RuntimeException e) {
            LOG.warn("musicbrainz unexpected host={} reason={}", uri.getHost(), e.getMessage());
            return null;
        }
    }

    private JsonNode getWithRetry(final URI uri) {
        int attempt = 0;
        while (true) {
            throttle();
            try {
                return restClient.get().uri(uri).retrieve().body(JsonNode.class);
            } catch (final HttpServerErrorException e) {
                if (e.getStatusCode().value() != HTTP_SERVICE_UNAVAILABLE) {
                    throw e;
                }
                if (attempt >= properties.maxRetries()) {
                    throw new MusicBrainzRateLimitException(
                            "musicbrainz 503 rate limit exhausted after " + (attempt + 1) + " attempts");
                }
                final long backoffMs = properties.backoff().toMillis() * (1L << attempt);
                LOG.warn("musicbrainz 503 host={} attempt={} backoffMs={}", uri.getHost(), attempt + 1, backoffMs);
                sleep(backoffMs);
                attempt++;
            }
        }
    }

    /** per-instance 1 req/s throttle — 직전 호출과의 간격이 {@code throttle} 미만이면 그 차이만큼 sleep. */
    private void throttle() {
        final long intervalMs = properties.throttle().toMillis();
        if (intervalMs <= 0) {
            return;
        }
        final long now = clockMillis.getAsLong();
        final long waitMs = lastRequestAtMillis.get() + intervalMs - now;
        if (waitMs > 0) {
            sleep(waitMs);
        }
        lastRequestAtMillis.set(now + Math.max(0L, waitMs));
    }

    private void sleep(final long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            sleeper.sleep(millis);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static MusicBrainzMatch toMatch(final JsonNode recording) {
        final String mbId = recording.hasNonNull("id") ? recording.get("id").asText() : null;
        final int score = recording.path("score").asInt(0);
        return new MusicBrainzMatch(mbId, firstIsrc(recording), score);
    }

    private static String firstIsrc(final JsonNode node) {
        final JsonNode isrcs = node.path("isrcs");
        if (!isrcs.isArray() || isrcs.isEmpty()) {
            return null;
        }
        final JsonNode first = isrcs.get(0);
        return first.isTextual() && !first.asText().isBlank() ? first.asText() : null;
    }

    private URI buildSearchUri(final String title, final String artist) {
        final String query = "recording:\"" + title + "\" AND artist:\"" + artist + "\"";
        return UriComponentsBuilder.fromUriString(properties.baseUrl())
                .path("/recording")
                .queryParam("query", query)
                .queryParam("fmt", "json")
                .queryParam("limit", properties.searchLimit())
                .build()
                .encode()
                .toUri();
    }

    private URI buildLookupUri(final String mbId) {
        return UriComponentsBuilder.fromUriString(properties.baseUrl())
                .path("/recording/{mbid}")
                .queryParam("inc", "isrcs")
                .queryParam("fmt", "json")
                .build(mbId);
    }

    private static RestClient defaultRestClient(final MusicBrainzProperties properties) {
        return RestClient.builder()
                .requestFactory(buildRequestFactory(properties.requestTimeout()))
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent())
                .build();
    }

    private static ClientHttpRequestFactory buildRequestFactory(final Duration timeout) {
        final SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) timeout.toMillis());
        factory.setReadTimeout((int) timeout.toMillis());
        return factory;
    }

    /** Thread 인터럽트 전파를 검사 예외로 표현하는 테스트 주입용 sleep 추상. 기본 구현은 {@link Thread#sleep(long)}. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
