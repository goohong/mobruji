package com.mobruji.song.application.albumcover;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Optional;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link CoverArtArchiveAlbumCoverClient} 단위 테스트. MockRestServiceServer 로 2단계 외부 호출
 * (MusicBrainz release 검색 → Cover Art Archive front cover) 을 stub.
 *
 * <p>검증 포인트:
 * <ul>
 * <li>MusicBrainz hit + CAA front 이미지 → thumbnails 500 URL 반환.</li>
 * <li>MusicBrainz miss (빈 releases) → empty, CAA 미호출.</li>
 * <li>release 에 id 누락 → empty.</li>
 * <li>CAA 404 (커버 미등록) → empty (graceful).</li>
 * <li>CAA front 이미지 없음 → empty.</li>
 * <li>thumbnails 없는 front → 원본 image URL fallback.</li>
 * <li>MusicBrainz 타임아웃 → empty.</li>
 * <li>title/artist null/blank → 호출 없이 empty.</li>
 * <li>extractFrontImageUrl 우선순위 (500 → large → image) + front 부재 → empty.</li>
 * </ul>
 */
class CoverArtArchiveAlbumCoverClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
    @DisplayName("lookup: MusicBrainz hit → CAA front 이미지 thumbnails 500 URL 반환")
    void lookup_success_returnsFront500Url() {
        // given
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(),
                requestTo(Matchers.containsString("musicbrainz.org/ws/2/release/")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"releases\":[{\"id\":\"mbid-123\"}]}", MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(),
                requestTo("https://coverartarchive.org/release/mbid-123"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "images": [{
                            "front": true,
                            "image": "https://coverartarchive.org/release/mbid-123/full.jpg",
                            "thumbnails": {
                              "500": "https://coverartarchive.org/release/mbid-123/500.jpg",
                              "large": "https://coverartarchive.org/release/mbid-123/large.jpg"
                            }
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));
        final CoverArtArchiveAlbumCoverClient client = new CoverArtArchiveAlbumCoverClient(PROPERTIES, builder.build());

        // when
        final Optional<String> result = client.lookupAlbumCoverUrl("벚꽃 엔딩", "버스커 버스커");

        // then
        assertThat(result).contains("https://coverartarchive.org/release/mbid-123/500.jpg");
        server.verify();
    }

    @Test
    @DisplayName("lookup: MusicBrainz 빈 releases 면 empty (CAA 미호출)")
    void lookup_musicBrainzMiss_returnsEmptyWithoutCoverArtCall() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(Matchers.any(String.class)))
                .andRespond(withSuccess("{\"releases\":[]}", MediaType.APPLICATION_JSON));
        final CoverArtArchiveAlbumCoverClient client = new CoverArtArchiveAlbumCoverClient(PROPERTIES, builder.build());

        final Optional<String> result = client.lookupAlbumCoverUrl("t", "a");

        assertThat(result).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("lookup: release 에 id 누락이면 empty")
    void lookup_releaseWithoutId_returnsEmpty() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(Matchers.any(String.class)))
                .andRespond(withSuccess(
                        "{\"releases\":[{\"title\":\"x\"}]}", MediaType.APPLICATION_JSON));
        final CoverArtArchiveAlbumCoverClient client = new CoverArtArchiveAlbumCoverClient(PROPERTIES, builder.build());

        final Optional<String> result = client.lookupAlbumCoverUrl("t", "a");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("lookup: CAA 404 (커버 미등록) 면 empty (graceful, 예외 전파 없음)")
    void lookup_coverArtNotFound_returnsEmpty() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(Matchers.containsString("musicbrainz")))
                .andRespond(withSuccess(
                        "{\"releases\":[{\"id\":\"mbid-404\"}]}", MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(Matchers.containsString("coverartarchive")))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        final CoverArtArchiveAlbumCoverClient client = new CoverArtArchiveAlbumCoverClient(PROPERTIES, builder.build());

        final Optional<String> result = client.lookupAlbumCoverUrl("t", "a");

        assertThat(result).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("lookup: CAA 응답에 front 이미지 없으면 empty")
    void lookup_noFrontImage_returnsEmpty() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(Matchers.containsString("musicbrainz")))
                .andRespond(withSuccess(
                        "{\"releases\":[{\"id\":\"mbid-x\"}]}", MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(Matchers.containsString("coverartarchive")))
                .andRespond(withSuccess(
                        "{\"images\":[{\"front\":false,\"image\":\"https://x/back.jpg\"}]}",
                        MediaType.APPLICATION_JSON));
        final CoverArtArchiveAlbumCoverClient client = new CoverArtArchiveAlbumCoverClient(PROPERTIES, builder.build());

        final Optional<String> result = client.lookupAlbumCoverUrl("t", "a");

        assertThat(result).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("lookup: front 이미지에 thumbnails 없으면 원본 image URL fallback")
    void lookup_frontWithoutThumbnails_returnsImage() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(Matchers.containsString("musicbrainz")))
                .andRespond(withSuccess(
                        "{\"releases\":[{\"id\":\"mbid-y\"}]}", MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(Matchers.containsString("coverartarchive")))
                .andRespond(withSuccess(
                        "{\"images\":[{\"front\":true,\"image\":\"https://x/full.jpg\"}]}",
                        MediaType.APPLICATION_JSON));
        final CoverArtArchiveAlbumCoverClient client = new CoverArtArchiveAlbumCoverClient(PROPERTIES, builder.build());

        final Optional<String> result = client.lookupAlbumCoverUrl("t", "a");

        assertThat(result).contains("https://x/full.jpg");
        server.verify();
    }

    @Test
    @DisplayName("lookup: MusicBrainz 타임아웃이어도 예외 전파 없이 empty")
    void lookup_musicBrainzTimeout_returnsEmpty() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(Matchers.any(String.class)))
                .andRespond(withException(new SocketTimeoutException("read timed out")));
        final CoverArtArchiveAlbumCoverClient client = new CoverArtArchiveAlbumCoverClient(PROPERTIES, builder.build());

        final Optional<String> result = client.lookupAlbumCoverUrl("t", "a");

        assertThat(result).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("lookup: title 또는 artist 가 null/blank 이면 호출 없이 empty")
    void lookup_blankInput_returnsEmptyWithoutCall() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        final CoverArtArchiveAlbumCoverClient client = new CoverArtArchiveAlbumCoverClient(PROPERTIES, builder.build());

        assertThat(client.lookupAlbumCoverUrl(null, "a")).isEmpty();
        assertThat(client.lookupAlbumCoverUrl("", "a")).isEmpty();
        assertThat(client.lookupAlbumCoverUrl("t", null)).isEmpty();
        assertThat(client.lookupAlbumCoverUrl("t", "  ")).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("extractFrontImageUrl: thumbnails 500 우선")
    void extractFront_prefers500() throws Exception {
        final JsonNode images = MAPPER.readTree(
                "[{\"front\":true,\"image\":\"i\",\"thumbnails\":{\"500\":\"u500\",\"large\":\"ul\"}}]");
        assertThat(CoverArtArchiveAlbumCoverClient.extractFrontImageUrl(images)).contains("u500");
    }

    @Test
    @DisplayName("extractFrontImageUrl: 500 없으면 large fallback")
    void extractFront_fallbackLarge() throws Exception {
        final JsonNode images = MAPPER.readTree(
                "[{\"front\":true,\"image\":\"i\",\"thumbnails\":{\"large\":\"ul\"}}]");
        assertThat(CoverArtArchiveAlbumCoverClient.extractFrontImageUrl(images)).contains("ul");
    }

    @Test
    @DisplayName("extractFrontImageUrl: thumbnails 없으면 image fallback")
    void extractFront_fallbackImage() throws Exception {
        final JsonNode images = MAPPER.readTree("[{\"front\":true,\"image\":\"i\"}]");
        assertThat(CoverArtArchiveAlbumCoverClient.extractFrontImageUrl(images)).contains("i");
    }

    @Test
    @DisplayName("extractFrontImageUrl: front 이미지 없으면 empty")
    void extractFront_noFront_returnsEmpty() throws Exception {
        final JsonNode images = MAPPER.readTree("[{\"front\":false,\"image\":\"i\"}]");
        assertThat(CoverArtArchiveAlbumCoverClient.extractFrontImageUrl(images)).isEmpty();
    }
}
