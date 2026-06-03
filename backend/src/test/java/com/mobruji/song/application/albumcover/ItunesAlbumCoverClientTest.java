package com.mobruji.song.application.albumcover;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.web.client.RestClient;

/**
 * {@link ItunesAlbumCoverClient} 단위 테스트. MockRestServiceServer 로 외부 호출을 stub.
 *
 * <p>검증 포인트:
 * <ul>
 * <li>성공 응답 → artworkUrl100 의 100x100 을 600x600 으로 upscale 한 URL 반환.</li>
 * <li>resultCount=0 / 빈 results → empty.</li>
 * <li>artworkUrl100 필드 누락 → empty.</li>
 * <li>HTTP 5xx → empty (graceful, 예외 전파 없음).</li>
 * <li>IO 실패 (network) → empty.</li>
 * <li>title/artist 가 null/blank → 호출 없이 empty.</li>
 * <li>upscale: 100x100 토큰 없으면 원본 그대로.</li>
 * </ul>
 *
 * <p>이슈 #508 회귀 가드 보강:
 * <ul>
 * <li>타임아웃 ({@link org.springframework.web.client.ResourceAccessException}) → empty.</li>
 * <li>HTTP 4xx → empty (5xx 와 별도 분기).</li>
 * <li>body == null → empty.</li>
 * <li>"results" 키 부재 → empty (빈 results 와 구분되는 분기).</li>
 * <li>artworkUrl100 가 명시적 null → empty ({@code hasNonNull} false).</li>
 * <li>term 쿼리 파라미터에 title + " " + artist 가 정확히 결합.</li>
 * </ul>
 */
class ItunesAlbumCoverClientTest {

    private static final AlbumCoverProperties PROPERTIES = new AlbumCoverProperties(
            new AlbumCoverProperties.Itunes(
                    "https://itunes.apple.com/search",
                    "KR",
                    Duration.ofSeconds(5),
                    Duration.ZERO,
                    "600x600"),
            new AlbumCoverProperties.CoverArtArchive(
                    "https://musicbrainz.org/ws/2",
                    "https://coverartarchive.org",
                    "mobruji-backend/0.1 (+test)",
                    Duration.ofSeconds(5)));

    @Test
    @DisplayName("lookupAlbumCoverUrl: 성공 응답이면 artworkUrl100 의 100x100 을 600x600 으로 치환한 URL 반환")
    void lookup_success_returnsUpscaledUrl() {
        // given
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        final String body = """
                {
                  "resultCount": 1,
                  "results": [{
                    "artworkUrl100": "https://is1-ssl.mzstatic.com/image/thumb/Music/abc/100x100bb.jpg"
                  }]
                }
                """;
        server.expect(ExpectedCount.once(),
                requestTo(org.hamcrest.Matchers.containsString("itunes.apple.com/search")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(MockRestRequestMatchers.queryParam("entity", "song"))
                .andExpect(MockRestRequestMatchers.queryParam("country", "KR"))
                .andExpect(MockRestRequestMatchers.queryParam("limit", "1"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        final ItunesAlbumCoverClient client = new ItunesAlbumCoverClient(PROPERTIES, builder.build());

        // when
        final Optional<String> result = client.lookupAlbumCoverUrl("벚꽃 엔딩", "버스커 버스커");

        // then
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(
                "https://is1-ssl.mzstatic.com/image/thumb/Music/abc/600x600bb.jpg");
        server.verify();
    }

    @Test
    @DisplayName("lookupAlbumCoverUrl: resultCount=0 응답이면 empty")
    void lookup_emptyResults_returnsEmpty() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(org.hamcrest.Matchers.any(String.class)))
                .andRespond(withSuccess("{\"resultCount\":0,\"results\":[]}", MediaType.APPLICATION_JSON));
        final ItunesAlbumCoverClient client = new ItunesAlbumCoverClient(PROPERTIES, builder.build());

        final Optional<String> result = client.lookupAlbumCoverUrl("unknown title", "unknown artist");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("lookupAlbumCoverUrl: artworkUrl100 필드 누락이면 empty")
    void lookup_missingArtwork_returnsEmpty() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(org.hamcrest.Matchers.any(String.class)))
                .andRespond(withSuccess(
                        "{\"resultCount\":1,\"results\":[{\"trackName\":\"x\"}]}",
                        MediaType.APPLICATION_JSON));
        final ItunesAlbumCoverClient client = new ItunesAlbumCoverClient(PROPERTIES, builder.build());

        final Optional<String> result = client.lookupAlbumCoverUrl("t", "a");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("lookupAlbumCoverUrl: HTTP 5xx 응답이어도 예외 전파 없이 empty")
    void lookup_serverError_returnsEmpty() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(org.hamcrest.Matchers.any(String.class)))
                .andRespond(withServerError());
        final ItunesAlbumCoverClient client = new ItunesAlbumCoverClient(PROPERTIES, builder.build());

        final Optional<String> result = client.lookupAlbumCoverUrl("t", "a");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("lookupAlbumCoverUrl: IO 실패 (network) 도 empty")
    void lookup_ioException_returnsEmpty() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(org.hamcrest.Matchers.any(String.class)))
                .andRespond(withException(new IOException("connection reset")));
        final ItunesAlbumCoverClient client = new ItunesAlbumCoverClient(PROPERTIES, builder.build());

        final Optional<String> result = client.lookupAlbumCoverUrl("t", "a");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("lookupAlbumCoverUrl: title 또는 artist 가 null/blank 이면 호출 없이 empty")
    void lookup_blankInput_returnsEmptyWithoutCall() {
        final RestClient.Builder builder = RestClient.builder();
        // MockRestServiceServer 등록은 하되 expect 없음 — verify 시 호출 0회 보장.
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        final ItunesAlbumCoverClient client = new ItunesAlbumCoverClient(PROPERTIES, builder.build());

        assertThat(client.lookupAlbumCoverUrl(null, "a")).isEmpty();
        assertThat(client.lookupAlbumCoverUrl("", "a")).isEmpty();
        assertThat(client.lookupAlbumCoverUrl("t", null)).isEmpty();
        assertThat(client.lookupAlbumCoverUrl("t", "  ")).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("upscale: 100x100 토큰 있으면 thumbResolution 으로 치환")
    void upscale_replaces100x100() {
        final String result = ItunesAlbumCoverClient.upscale(
                "https://x.example.com/foo/100x100bb.jpg", "600x600");
        assertThat(result).isEqualTo("https://x.example.com/foo/600x600bb.jpg");
    }

    @Test
    @DisplayName("upscale: 100x100 토큰 없으면 원본 그대로 (안전 fallback)")
    void upscale_noToken_returnsOriginal() {
        final String original = "https://x.example.com/foo/200x200bb.jpg";
        final String result = ItunesAlbumCoverClient.upscale(original, "600x600");
        assertThat(result).isEqualTo(original);
    }

    @Test
    @DisplayName("upscale: null 입력은 null 반환")
    void upscale_null_returnsNull() {
        assertThat(ItunesAlbumCoverClient.upscale(null, "600x600")).isNull();
    }

    @Test
    @DisplayName("lookupAlbumCoverUrl: 타임아웃 (SocketTimeoutException → ResourceAccessException) 도 empty")
    void lookup_timeout_returnsEmpty() {
        // given
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(org.hamcrest.Matchers.any(String.class)))
                .andRespond(withException(new SocketTimeoutException("read timed out")));
        final ItunesAlbumCoverClient client = new ItunesAlbumCoverClient(PROPERTIES, builder.build());

        // when
        final Optional<String> result = client.lookupAlbumCoverUrl("t", "a");

        // then
        assertThat(result).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("lookupAlbumCoverUrl: HTTP 4xx 응답이어도 예외 전파 없이 empty (5xx 분기와 별도)")
    void lookup_clientError_returnsEmpty() {
        // given
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(org.hamcrest.Matchers.any(String.class)))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        final ItunesAlbumCoverClient client = new ItunesAlbumCoverClient(PROPERTIES, builder.build());

        // when
        final Optional<String> result = client.lookupAlbumCoverUrl("t", "a");

        // then
        assertThat(result).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("lookupAlbumCoverUrl: 응답 body 가 null 이면 empty (graceful)")
    void lookup_nullBody_returnsEmpty() {
        // given — 204 No Content 응답이면 RestClient.body(JsonNode.class) 가 null 반환.
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(org.hamcrest.Matchers.any(String.class)))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        final ItunesAlbumCoverClient client = new ItunesAlbumCoverClient(PROPERTIES, builder.build());

        // when
        final Optional<String> result = client.lookupAlbumCoverUrl("t", "a");

        // then
        assertThat(result).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("lookupAlbumCoverUrl: 'results' 키 자체가 부재한 응답이면 empty (빈 results 와 구분되는 분기)")
    void lookup_resultsKeyMissing_returnsEmpty() {
        // given
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(org.hamcrest.Matchers.any(String.class)))
                .andRespond(withSuccess("{\"resultCount\":0}", MediaType.APPLICATION_JSON));
        final ItunesAlbumCoverClient client = new ItunesAlbumCoverClient(PROPERTIES, builder.build());

        // when
        final Optional<String> result = client.lookupAlbumCoverUrl("t", "a");

        // then
        assertThat(result).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("lookupAlbumCoverUrl: artworkUrl100 이 명시적 null 이면 empty (hasNonNull false 분기)")
    void lookup_artworkExplicitNull_returnsEmpty() {
        // given
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(org.hamcrest.Matchers.any(String.class)))
                .andRespond(withSuccess(
                        "{\"resultCount\":1,\"results\":[{\"artworkUrl100\":null}]}",
                        MediaType.APPLICATION_JSON));
        final ItunesAlbumCoverClient client = new ItunesAlbumCoverClient(PROPERTIES, builder.build());

        // when
        final Optional<String> result = client.lookupAlbumCoverUrl("t", "a");

        // then
        assertThat(result).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("lookupAlbumCoverUrl: term 쿼리에 title + ' ' + artist 가 정확히 결합 (공백은 %20 으로 인코딩)")
    void lookup_termQueryParam_combinesTitleAndArtist() {
        // given — UriComponentsBuilder.encode() 가 적용된 후의 raw query 값을 검증.
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(org.hamcrest.Matchers.any(String.class)))
                .andExpect(method(HttpMethod.GET))
                .andExpect(MockRestRequestMatchers.queryParam("term", "cherry%20blossom%20busker"))
                .andRespond(withSuccess("{\"resultCount\":0,\"results\":[]}", MediaType.APPLICATION_JSON));
        final ItunesAlbumCoverClient client = new ItunesAlbumCoverClient(PROPERTIES, builder.build());

        // when
        final Optional<String> result = client.lookupAlbumCoverUrl("cherry blossom", "busker");

        // then — term 결합 검증이 핵심, 응답은 비어 있어 empty.
        assertThat(result).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("lookupAlbumCoverUrl: term 은 괄호/feat. 노이즈를 제거한 정규화 값으로 결합 (ADR 0029 매칭율 보강)")
    void lookup_termQueryParam_usesNormalizedTerms() {
        // given — title 의 (Live) 와 artist 의 feat. 절이 검색 전에 제거돼야 한다.
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(org.hamcrest.Matchers.any(String.class)))
                .andExpect(method(HttpMethod.GET))
                .andExpect(MockRestRequestMatchers.queryParam("term", "cherry%20blossom%20busker"))
                .andRespond(withSuccess("{\"resultCount\":0,\"results\":[]}", MediaType.APPLICATION_JSON));
        final ItunesAlbumCoverClient client = new ItunesAlbumCoverClient(PROPERTIES, builder.build());

        // when
        final Optional<String> result = client.lookupAlbumCoverUrl(
                "cherry blossom (Live)", "busker feat. friend");

        // then
        assertThat(result).isEmpty();
        server.verify();
    }
}
