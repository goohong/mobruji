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
 * iTunes Search API ({@code https://itunes.apple.com/search}) 어댑터 — 이슈 #322 PR B.
 *
 * <p>요청: {@code GET {baseUrl}?term={title}+{artist}&entity=song&country={country}&limit=1}.
 * {@code term} 은 {@link AlbumCoverSearchTerms#normalize(String)} 로 괄호/{@code feat.} 노이즈를 제거한 뒤 결합한다.
 * 응답: {@code {"resultCount": N, "results": [{ "artworkUrl100": "...100x100bb.jpg" }, ...]}}.
 *
 * <p>응답의 {@code artworkUrl100} 의 `100x100` 부분을 properties 의 thumbResolution (기본 `600x600`)
 * 으로 치환해 고해상도 URL 을 만든다. iTunes 의 CDN 패턴 ({@code .../100x100bb.jpg}) 가 동일 URL 의
 * 사이즈만 다르게 응답하므로 안전한 변환이다.
 *
 * <p>graceful degradation — 외부 호출/parse 가 어떤 이유로든 실패해도 {@link Optional#empty()} 를
 * 반환하고 절대 예외를 throw 하지 않는다. backfill batch 가 곡 단위로 진행되도록 보장.
 *
 * <p>로깅 정책 — 검색어(원문)는 INFO 노출 금지(곡 제목/아티스트는 PII 는 아니지만 URL 패턴 보존).
 * host + status + elapsedMs 위주.
 */
@Component
public class ItunesAlbumCoverClient implements AlbumCoverLookupClient {

    private static final Logger LOG = LoggerFactory.getLogger(ItunesAlbumCoverClient.class);

    /** iTunes Search API 의 artworkUrl100 sentinel — 모든 응답이 동일한 100x100 표기를 사용한다. */
    static final String ARTWORK_DEFAULT_DIMENSION = "100x100";

    private final AlbumCoverProperties.Itunes properties;
    private final RestClient restClient;

    @Autowired
    public ItunesAlbumCoverClient(final AlbumCoverProperties properties) {
        this.properties = properties.itunes();
        this.restClient = RestClient.builder()
                .requestFactory(buildRequestFactory(this.properties.requestTimeout()))
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.USER_AGENT, "mobruji-backend/0.1 (+itunes-album-cover)")
                .build();
    }

    /**
     * 테스트 주입용 — 단위 테스트에서 stub server 의 RestClient 를 그대로 넣을 수 있도록 추가 생성자를 제공한다.
     */
    ItunesAlbumCoverClient(final AlbumCoverProperties properties, final RestClient restClient) {
        this.properties = properties.itunes();
        this.restClient = Objects.requireNonNull(restClient, "restClient must not be null");
    }

    @Override
    public Optional<String> lookupAlbumCoverUrl(final String title, final String artist) {
        if (title == null || title.isBlank() || artist == null || artist.isBlank()) {
            return Optional.empty();
        }
        final URI uri = buildSearchUri(title, artist);
        final long startedAt = System.currentTimeMillis();
        try {
            final JsonNode body = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(JsonNode.class);
            final long elapsedMs = System.currentTimeMillis() - startedAt;
            if (body == null || !body.has("results") || body.get("results").isEmpty()) {
                LOG.info("itunes lookup miss host={} elapsedMs={}", uri.getHost(), elapsedMs);
                return Optional.empty();
            }
            final JsonNode first = body.get("results").get(0);
            if (!first.hasNonNull("artworkUrl100")) {
                LOG.info("itunes lookup no-artwork host={} elapsedMs={}", uri.getHost(), elapsedMs);
                return Optional.empty();
            }
            final String artworkUrl100 = first.get("artworkUrl100").asText();
            final String highRes = upscale(artworkUrl100, properties.thumbResolution());
            LOG.info("itunes lookup ok host={} elapsedMs={}", uri.getHost(), elapsedMs);
            return Optional.of(highRes);
        } catch (final ResourceAccessException e) {
            LOG.warn("itunes lookup timeout host={} reason={}", uri.getHost(), e.getMessage());
            return Optional.empty();
        } catch (final RestClientException e) {
            LOG.warn("itunes lookup http error host={} reason={}", uri.getHost(), e.getMessage());
            return Optional.empty();
        } catch (final RuntimeException e) {
            LOG.warn("itunes lookup unexpected host={} reason={}", uri.getHost(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * `https://.../100x100bb.jpg` → `https://.../600x600bb.jpg` 로 치환. iTunes CDN 의 동일 URL 패턴이라
     * 단순 string replace 로 안전하다. {@code 100x100} 토큰이 없으면 원본 URL 을 그대로 반환한다.
     */
    static String upscale(final String artworkUrl100, final String thumbResolution) {
        if (artworkUrl100 == null) {
            return null;
        }
        if (!artworkUrl100.contains(ARTWORK_DEFAULT_DIMENSION)) {
            return artworkUrl100;
        }
        return artworkUrl100.replace(ARTWORK_DEFAULT_DIMENSION, thumbResolution);
    }

    private URI buildSearchUri(final String title, final String artist) {
        final String term = AlbumCoverSearchTerms.normalize(title)
                + " " + AlbumCoverSearchTerms.normalize(artist);
        return UriComponentsBuilder.fromUriString(properties.baseUrl())
                .queryParam("term", term)
                .queryParam("entity", "song")
                .queryParam("country", properties.country())
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
