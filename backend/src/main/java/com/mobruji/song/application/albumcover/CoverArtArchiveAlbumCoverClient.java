package com.mobruji.song.application.albumcover;

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
 * MusicBrainz + Cover Art Archive 어댑터 — ADR 0029 의 폴백 출처. iTunes 무매칭 곡 (한국 가요/인디/구곡)
 * 의 커버리지를 보완한다.
 *
 * <p>2단계 lookup:
 * <ol>
 * <li>MusicBrainz release 검색 ({@code GET {mbBaseUrl}/release/?query=release:"{title}" AND
 * artist:"{artist}"&fmt=json&limit=1})
 * → 최상위 release 의 MBID 추출.</li>
 * <li>Cover Art Archive ({@code GET {caaBaseUrl}/release/{mbid}}) → {@code images[]} 중 {@code front=true}
 * 이미지의 URL 반환. 커버 미등록 release 는 404 → empty.</li>
 * </ol>
 *
 * <p>약관 준수 — MusicBrainz/CAA 모두 식별 가능한 {@code User-Agent} (contact 포함) 를 의무화한다
 * (properties.userAgent). 이미지 바이트는 저장하지 않고 CAA URL 문자열만 backfill 결과로 영속화한다
 * (CC0/CC 라이선스라 재호스팅 제약은 느슨하나 ADR 0029 의 "URL 만 캐싱" 정책을 iTunes 와 일관 유지).
 *
 * <p>graceful degradation — 외부 호출/parse 가 어떤 이유로든 실패하거나 매칭이 없으면 {@link Optional#empty()}
 * 를 반환하고 절대 예외를 throw 하지 않는다. backfill batch 가 곡 단위로 진행되도록 보장.
 *
 * <p>로깅 정책 — 검색어(원문)는 노출하지 않고 host + status + elapsedMs 위주 ({@link ItunesAlbumCoverClient}
 * 와 동일).
 */
@Component
public class CoverArtArchiveAlbumCoverClient implements AlbumCoverLookupClient {

    private static final Logger LOG = LoggerFactory.getLogger(CoverArtArchiveAlbumCoverClient.class);

    private final AlbumCoverProperties.CoverArtArchive properties;
    private final RestClient restClient;

    @Autowired
    public CoverArtArchiveAlbumCoverClient(final AlbumCoverProperties properties) {
        this.properties = properties.coverArtArchive();
        this.restClient = RestClient.builder()
                .requestFactory(buildRequestFactory(this.properties.requestTimeout()))
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.USER_AGENT, this.properties.userAgent())
                .build();
    }

    /**
     * 테스트 주입용 — 단위 테스트에서 stub server 의 RestClient 를 그대로 넣을 수 있도록 추가 생성자를 제공한다.
     */
    CoverArtArchiveAlbumCoverClient(
            final AlbumCoverProperties properties, final RestClient restClient) {
        this.properties = properties.coverArtArchive();
        this.restClient = Objects.requireNonNull(restClient, "restClient must not be null");
    }

    @Override
    public Optional<String> lookupAlbumCoverUrl(final String title, final String artist) {
        if (title == null || title.isBlank() || artist == null || artist.isBlank()) {
            return Optional.empty();
        }
        final Optional<String> mbid = resolveReleaseMbid(title, artist);
        if (mbid.isEmpty()) {
            return Optional.empty();
        }
        return resolveFrontCoverUrl(mbid.get());
    }

    private Optional<String> resolveReleaseMbid(final String title, final String artist) {
        final URI uri = buildReleaseSearchUri(title, artist);
        final long startedAt = System.currentTimeMillis();
        try {
            final JsonNode body = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(JsonNode.class);
            final long elapsedMs = System.currentTimeMillis() - startedAt;
            if (body == null || !body.has("releases") || body.get("releases").isEmpty()) {
                LOG.info("musicbrainz lookup miss host={} elapsedMs={}", uri.getHost(), elapsedMs);
                return Optional.empty();
            }
            final JsonNode first = body.get("releases").get(0);
            if (!first.hasNonNull("id")) {
                LOG.info("musicbrainz lookup no-mbid host={} elapsedMs={}", uri.getHost(), elapsedMs);
                return Optional.empty();
            }
            LOG.info("musicbrainz lookup ok host={} elapsedMs={}", uri.getHost(), elapsedMs);
            return Optional.of(first.get("id").asText());
        } catch (final ResourceAccessException e) {
            LOG.warn("musicbrainz lookup timeout host={} reason={}", uri.getHost(), e.getMessage());
            return Optional.empty();
        } catch (final RestClientException e) {
            LOG.warn("musicbrainz lookup http error host={} reason={}", uri.getHost(), e.getMessage());
            return Optional.empty();
        } catch (final RuntimeException e) {
            LOG.warn("musicbrainz lookup unexpected host={} reason={}", uri.getHost(), e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<String> resolveFrontCoverUrl(final String mbid) {
        final URI uri = buildCoverArtUri(mbid);
        final long startedAt = System.currentTimeMillis();
        try {
            final JsonNode body = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(JsonNode.class);
            final long elapsedMs = System.currentTimeMillis() - startedAt;
            if (body == null || !body.has("images") || body.get("images").isEmpty()) {
                LOG.info("coverart lookup miss host={} elapsedMs={}", uri.getHost(), elapsedMs);
                return Optional.empty();
            }
            final Optional<String> frontUrl = extractFrontImageUrl(body.get("images"));
            if (frontUrl.isEmpty()) {
                LOG.info("coverart lookup no-front host={} elapsedMs={}", uri.getHost(), elapsedMs);
                return Optional.empty();
            }
            LOG.info("coverart lookup ok host={} elapsedMs={}", uri.getHost(), elapsedMs);
            return frontUrl;
        } catch (final ResourceAccessException e) {
            LOG.warn("coverart lookup timeout host={} reason={}", uri.getHost(), e.getMessage());
            return Optional.empty();
        } catch (final RestClientException e) {
            // CAA 는 커버 미등록 release 에 404 응답 — graceful empty (정상 흐름).
            LOG.info("coverart lookup http miss host={} reason={}", uri.getHost(), e.getMessage());
            return Optional.empty();
        } catch (final RuntimeException e) {
            LOG.warn("coverart lookup unexpected host={} reason={}", uri.getHost(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * CAA 의 {@code images[]} 에서 {@code front=true} 이미지의 URL 을 추출한다. 해상도는 thumbnails 의
     * {@code 500} → {@code large} 순으로 선호하고, 둘 다 없으면 원본 {@code image} 를 사용한다 (iTunes 600x600
     * 과 유사한 카드용 중해상도 우선).
     */
    static Optional<String> extractFrontImageUrl(final JsonNode images) {
        for (final JsonNode image : images) {
            if (!image.path("front").asBoolean(false)) {
                continue;
            }
            final JsonNode thumbnails = image.path("thumbnails");
            if (thumbnails.hasNonNull("500")) {
                return Optional.of(thumbnails.get("500").asText());
            }
            if (thumbnails.hasNonNull("large")) {
                return Optional.of(thumbnails.get("large").asText());
            }
            if (image.hasNonNull("image")) {
                return Optional.of(image.get("image").asText());
            }
        }
        return Optional.empty();
    }

    private URI buildReleaseSearchUri(final String title, final String artist) {
        final String query = "release:\"" + title + "\" AND artist:\"" + artist + "\"";
        return UriComponentsBuilder.fromUriString(properties.musicBrainzBaseUrl())
                .path("/release/")
                .queryParam("query", query)
                .queryParam("fmt", "json")
                .queryParam("limit", 1)
                .build()
                .encode()
                .toUri();
    }

    private URI buildCoverArtUri(final String mbid) {
        return UriComponentsBuilder.fromUriString(properties.coverArtArchiveBaseUrl())
                .path("/release/{mbid}")
                .build(mbid);
    }

    private static ClientHttpRequestFactory buildRequestFactory(final Duration timeout) {
        final SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) timeout.toMillis());
        factory.setReadTimeout((int) timeout.toMillis());
        return factory;
    }
}
