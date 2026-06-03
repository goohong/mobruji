package com.mobruji.song.application.catalogimport;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * MusicBrainz recording 검색 어댑터 — spec {@code song-catalog-expansion.md} §5-3 의 1차 메타 출처(CC0).
 *
 * <p>단일 호출: {@code GET {baseUrl}/recording/?query=recording:"{title}" AND artist:"{artist}"&fmt=json&limit=1}
 * → 최상위 recording 에서 MBID / ISRC / 최초 발매연도 / 대표 장르 태그를 추출한다. 이미지·가사·원본 음원은
 * 가져오지 않는다 — 메타데이터만 영속 (spec §3 비기능, CLAUDE.md §4 보안).
 *
 * <p>약관 준수 — MusicBrainz anonymous 호출은 식별 가능한 {@code User-Agent}(contact 포함) 의무
 * ({@code properties.userAgent}). 호출 간격(1 req/s)은 배치({@link MetadataOnlyImportCommand}) 가 throttle 로 보장.
 *
 * <p>graceful degradation — 외부 호출/parse 실패 또는 무매칭이면 {@link Optional#empty()} 를 반환하고 절대 예외를
 * 던지지 않는다. 로깅은 검색어 원문 대신 host + status + elapsedMs 위주 (앨범 커버 client 와 동일 정책).
 */
@Component
public class MusicBrainzSongMetadataLookupClient implements SongMetadataLookupClient {

    private static final Logger LOG = LoggerFactory.getLogger(MusicBrainzSongMetadataLookupClient.class);

    private final CatalogImportProperties properties;
    private final RestClient restClient;

    @Autowired
    public MusicBrainzSongMetadataLookupClient(final CatalogImportProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .requestFactory(buildRequestFactory(properties.requestTimeout()))
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent())
                .build();
    }

    /**
     * 테스트 주입용 — 단위 테스트에서 stub server 의 RestClient 를 그대로 넣을 수 있도록 추가 생성자를 제공한다.
     */
    MusicBrainzSongMetadataLookupClient(
            final CatalogImportProperties properties, final RestClient restClient) {
        this.properties = properties;
        this.restClient = Objects.requireNonNull(restClient, "restClient must not be null");
    }

    @Override
    public Optional<ImportedSongMetadata> lookupMetadata(final String title, final String artist) {
        if (title == null || title.isBlank() || artist == null || artist.isBlank()) {
            return Optional.empty();
        }
        final URI uri = buildRecordingSearchUri(title, artist);
        final long startedAt = System.currentTimeMillis();
        try {
            final JsonNode body = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(JsonNode.class);
            final long elapsedMs = System.currentTimeMillis() - startedAt;
            if (body == null || !body.has("recordings") || body.get("recordings").isEmpty()) {
                LOG.info("musicbrainz recording miss host={} elapsedMs={}", uri.getHost(), elapsedMs);
                return Optional.empty();
            }
            LOG.info("musicbrainz recording ok host={} elapsedMs={}", uri.getHost(), elapsedMs);
            return Optional.of(extractMetadata(body.get("recordings").get(0)));
        } catch (final ResourceAccessException e) {
            LOG.warn("musicbrainz recording timeout host={} reason={}", uri.getHost(), e.getMessage());
            return Optional.empty();
        } catch (final RestClientException e) {
            LOG.warn("musicbrainz recording http error host={} reason={}", uri.getHost(), e.getMessage());
            return Optional.empty();
        } catch (final RuntimeException e) {
            LOG.warn("musicbrainz recording unexpected host={} reason={}", uri.getHost(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * MusicBrainz recording JSON 노드에서 메타데이터를 추출한다. 각 필드는 부재 시 null 로 둔다 (graceful).
     *
     * <ul>
     * <li>{@code mbId}: recording {@code id}.</li>
     * <li>{@code isrc}: {@code isrcs[0]} (있으면).</li>
     * <li>{@code releaseYear}: {@code first-release-date} 앞 4자리.</li>
     * <li>{@code genre}: {@code tags[]} 중 {@code count} 최댓값 태그명 (동률이면 먼저 등장한 것).</li>
     * </ul>
     */
    static ImportedSongMetadata extractMetadata(final JsonNode recording) {
        final String mbId = recording.hasNonNull("id") ? recording.get("id").asText() : null;
        final String isrc = extractFirstIsrc(recording);
        final Integer releaseYear = extractReleaseYear(recording);
        final String genre = extractTopTag(recording);
        return new ImportedSongMetadata(mbId, isrc, releaseYear, genre);
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

    private URI buildRecordingSearchUri(final String title, final String artist) {
        final String query = "recording:\"" + title + "\" AND artist:\"" + artist + "\"";
        return UriComponentsBuilder.fromUriString(properties.musicBrainzBaseUrl())
                .path("/recording/")
                .queryParam("query", query)
                .queryParam("fmt", "json")
                .queryParam("limit", 1)
                .build()
                .encode()
                .toUri();
    }

    private static ClientHttpRequestFactory buildRequestFactory(final Duration timeout) {
        final SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) timeout.toMillis());
        factory.setReadTimeout((int) timeout.toMillis());
        return factory;
    }
}
