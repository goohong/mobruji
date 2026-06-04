package com.mobruji.song.application.catalogimport;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
 * MusicBrainz recording 검색 어댑터(browse 모드) — spec {@code song-catalog-expansion.md} §5-3 의 1차 메타 출처(CC0)
 * 를 아티스트 단위 페이징으로 사용해 한국 대중가요를 대량 수집한다 (#1705).
 *
 * <p>호출: {@code GET {baseUrl}/recording?query=artist:"{artist}"&fmt=json&limit={limit}&offset={offset}} →
 * 응답 {@code recordings[]} 각각에서 MBID/제목/대표 아티스트/ISRC/최초 발매연도/대표 장르 태그를 추출한다.
 * 이미지·가사·원본 음원은 가져오지 않는다 — 메타데이터만 영속 (spec §3 비기능, CLAUDE.md §4 보안).
 *
 * <p>약관 준수 — contact 포함 식별 {@code User-Agent}({@code properties.userAgent}) 를 부착하고, 호출 직전
 * per-instance throttle 로 1 req/s rate limit 을 강제한다 ({@link MusicBrainzBrowseClient} 단일 인스턴스 가정).
 * 503 응답은 지수 backoff(base × 2^attempt, {@code maxRetries} 회)로 재시도하고, 소진 시
 * {@link CatalogBrowseRateLimitException} 으로 batch 중단을 신호한다.
 *
 * <p>graceful — 503 소진을 제외한 모든 실패(4xx/기타 5xx/timeout/무매칭/parse)는 빈 목록으로 흡수한다.
 * 로깅은 검색어 원문 대신 host/status/elapsedMs 위주 (catalog import / album cover client 와 동일 정책).
 */
@Component
public class MusicBrainzBrowseClient implements CatalogBrowseClient {

    private static final Logger LOG = LoggerFactory.getLogger(MusicBrainzBrowseClient.class);
    private static final int HTTP_SERVICE_UNAVAILABLE = 503;

    private final CatalogBrowseProperties properties;
    private final RestClient restClient;
    private final LongSupplier clockMillis;
    private final Sleeper sleeper;
    private final AtomicLong lastRequestAtMillis = new AtomicLong(0L);

    @Autowired
    public MusicBrainzBrowseClient(final CatalogBrowseProperties properties) {
        this(properties, defaultRestClient(properties), System::currentTimeMillis, Thread::sleep);
    }

    /**
     * 테스트 주입용 — stub server 의 RestClient + 결정적 clock/sleeper 로 throttle/backoff 를 실시간 지연 없이 검증.
     */
    MusicBrainzBrowseClient(
            final CatalogBrowseProperties properties,
            final RestClient restClient,
            final LongSupplier clockMillis,
            final Sleeper sleeper) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.restClient = Objects.requireNonNull(restClient, "restClient must not be null");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis must not be null");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper must not be null");
    }

    @Override
    public List<BrowsedRecording> browseByArtist(final String artistName, final int limit, final int offset) {
        if (artistName == null || artistName.isBlank() || limit <= 0 || offset < 0) {
            return List.of();
        }
        final URI uri = buildSearchUri(artistName, limit, offset);
        final JsonNode body = getJsonGraceful(uri);
        if (body == null) {
            return List.of();
        }
        final JsonNode recordings = body.path("recordings");
        if (!recordings.isArray() || recordings.isEmpty()) {
            LOG.info("musicbrainz browse miss host={} offset={}", uri.getHost(), offset);
            return List.of();
        }
        final List<BrowsedRecording> result = new ArrayList<>(recordings.size());
        for (final JsonNode recording : recordings) {
            result.add(extractRecording(recording));
        }
        LOG.info("musicbrainz browse ok host={} offset={} count={}", uri.getHost(), offset, result.size());
        return result;
    }

    /**
     * recording JSON 노드에서 후보 메타를 추출한다. 각 필드는 부재 시 null (graceful).
     */
    static BrowsedRecording extractRecording(final JsonNode recording) {
        final String mbId = recording.hasNonNull("id") ? recording.get("id").asText() : null;
        final String title = recording.hasNonNull("title") ? recording.get("title").asText() : null;
        final String artist = extractArtist(recording);
        final String isrc = extractFirstIsrc(recording);
        final Integer releaseYear = extractReleaseYear(recording);
        final String genre = extractTopTag(recording);
        return new BrowsedRecording(mbId, title, artist, isrc, releaseYear, genre);
    }

    private static String extractArtist(final JsonNode recording) {
        final JsonNode credits = recording.path("artist-credit");
        if (!credits.isArray() || credits.isEmpty()) {
            return null;
        }
        final JsonNode first = credits.get(0);
        if (first.hasNonNull("name") && !first.get("name").asText().isBlank()) {
            return first.get("name").asText();
        }
        final JsonNode artist = first.path("artist");
        if (artist.hasNonNull("name") && !artist.get("name").asText().isBlank()) {
            return artist.get("name").asText();
        }
        return null;
    }

    private static String extractFirstIsrc(final JsonNode recording) {
        final JsonNode isrcs = recording.path("isrcs");
        if (!isrcs.isArray() || isrcs.isEmpty()) {
            return null;
        }
        final JsonNode first = isrcs.get(0);
        return first.isTextual() && !first.asText().isBlank() ? first.asText() : null;
    }

    private static Integer extractReleaseYear(final JsonNode recording) {
        final JsonNode date = recording.path("first-release-date");
        if (!date.isTextual() || date.asText().length() < 4) {
            return null;
        }
        try {
            return Integer.valueOf(date.asText().substring(0, 4));
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    private static String extractTopTag(final JsonNode recording) {
        final JsonNode tags = recording.path("tags");
        if (!tags.isArray() || tags.isEmpty()) {
            return null;
        }
        String bestName = null;
        int bestCount = Integer.MIN_VALUE;
        for (final JsonNode tag : tags) {
            if (!tag.hasNonNull("name")) {
                continue;
            }
            final int count = tag.path("count").asInt(0);
            if (count > bestCount) {
                bestCount = count;
                bestName = tag.get("name").asText();
            }
        }
        return bestName != null && !bestName.isBlank() ? bestName : null;
    }

    /**
     * throttle + 503 backoff 를 적용해 JSON 을 가져온다. 503 소진은 {@link CatalogBrowseRateLimitException} 으로
     * 전파하고, 그 외 모든 실패는 {@code null} 로 graceful 흡수한다.
     */
    private JsonNode getJsonGraceful(final URI uri) {
        try {
            return getWithRetry(uri);
        } catch (final CatalogBrowseRateLimitException e) {
            throw e;
        } catch (final ResourceAccessException e) {
            LOG.warn("musicbrainz browse timeout host={} reason={}", uri.getHost(), e.getMessage());
            return null;
        } catch (final RestClientException e) {
            LOG.warn("musicbrainz browse http error host={} reason={}", uri.getHost(), e.getMessage());
            return null;
        } catch (final RuntimeException e) {
            LOG.warn("musicbrainz browse unexpected host={} reason={}", uri.getHost(), e.getMessage());
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
                    throw new CatalogBrowseRateLimitException(
                            "musicbrainz browse 503 rate limit exhausted after " + (attempt + 1) + " attempts");
                }
                final long backoffMs = properties.backoff().toMillis() * (1L << attempt);
                LOG.warn("musicbrainz browse 503 host={} attempt={} backoffMs={}",
                        uri.getHost(), attempt + 1, backoffMs);
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

    private URI buildSearchUri(final String artistName, final int limit, final int offset) {
        final String query = "artist:\"" + artistName + "\"";
        return UriComponentsBuilder.fromUriString(properties.musicBrainzBaseUrl())
                .path("/recording")
                .queryParam("query", query)
                .queryParam("fmt", "json")
                .queryParam("limit", limit)
                .queryParam("offset", offset)
                .build()
                .encode()
                .toUri();
    }

    private static RestClient defaultRestClient(final CatalogBrowseProperties properties) {
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
