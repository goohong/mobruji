package com.mobruji.song.application.catalogimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
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

import org.hamcrest.Matchers;

/**
 * {@link MusicBrainzSongMetadataLookupClient} 단위 테스트 — MockRestServiceServer 로 외부 호출 stub.
 *
 * <p>검증 포인트: top-hit recording → mbId/isrc/releaseYear/genre 추출, 부분 누락 graceful,
 * 무매칭/HTTP 오류/타임아웃/null body → empty, blank 입력은 호출 없이 empty.
 */
class MusicBrainzSongMetadataLookupClientTest {

    private static final CatalogImportProperties PROPERTIES = new CatalogImportProperties(
            "https://musicbrainz.org/ws/2",
            "mobruji-backend/0.1 (+test)",
            Duration.ofSeconds(5),
            Duration.ZERO,
            0.3);

    @Test
    @DisplayName("lookupMetadata: top recording 에서 mbId/isrc/releaseYear/대표 태그 추출")
    void lookup_success_extractsMetadata() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        final String body = """
                {
                  "recordings": [{
                    "id": "mbid-abc",
                    "first-release-date": "2017-06-21",
                    "isrcs": ["KRA401700001"],
                    "tags": [
                      {"name": "ballad", "count": 1},
                      {"name": "k-pop", "count": 5}
                    ]
                  }]
                }
                """;
        server.expect(ExpectedCount.once(),
                MockRestRequestMatchers.requestTo(Matchers.containsString("musicbrainz.org/ws/2/recording")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(MockRestRequestMatchers.queryParam("fmt", "json"))
                .andExpect(MockRestRequestMatchers.queryParam("limit", "1"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        final MusicBrainzSongMetadataLookupClient client = new MusicBrainzSongMetadataLookupClient(PROPERTIES, builder
                .build());

        final Optional<ImportedSongMetadata> result = client.lookupMetadata("좋니", "윤종신");

        assertThat(result).isPresent();
        assertThat(result.get().mbId()).isEqualTo("mbid-abc");
        assertThat(result.get().isrc()).isEqualTo("KRA401700001");
        assertThat(result.get().releaseYear()).isEqualTo(2017);
        // count 최댓값 태그 선택.
        assertThat(result.get().genre()).isEqualTo("k-pop");
        server.verify();
    }

    @Test
    @DisplayName("lookupMetadata: isrcs/tags/first-release-date 누락이어도 mbId 만으로 graceful")
    void lookup_partialFields_graceful() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), MockRestRequestMatchers.requestTo(Matchers.any(String.class)))
                .andRespond(withSuccess("{\"recordings\":[{\"id\":\"mbid-x\"}]}", MediaType.APPLICATION_JSON));
        final MusicBrainzSongMetadataLookupClient client = new MusicBrainzSongMetadataLookupClient(PROPERTIES, builder
                .build());

        final Optional<ImportedSongMetadata> result = client.lookupMetadata("t", "a");

        assertThat(result).isPresent();
        assertThat(result.get().mbId()).isEqualTo("mbid-x");
        assertThat(result.get().isrc()).isNull();
        assertThat(result.get().releaseYear()).isNull();
        assertThat(result.get().genre()).isNull();
    }

    @Test
    @DisplayName("lookupMetadata: recordings 비었으면 empty")
    void lookup_emptyRecordings_returnsEmpty() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), MockRestRequestMatchers.requestTo(Matchers.any(String.class)))
                .andRespond(withSuccess("{\"recordings\":[]}", MediaType.APPLICATION_JSON));
        final MusicBrainzSongMetadataLookupClient client = new MusicBrainzSongMetadataLookupClient(PROPERTIES, builder
                .build());

        assertThat(client.lookupMetadata("t", "a")).isEmpty();
    }

    @Test
    @DisplayName("lookupMetadata: HTTP 5xx 도 예외 전파 없이 empty")
    void lookup_serverError_returnsEmpty() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), MockRestRequestMatchers.requestTo(Matchers.any(String.class)))
                .andRespond(withServerError());
        final MusicBrainzSongMetadataLookupClient client = new MusicBrainzSongMetadataLookupClient(PROPERTIES, builder
                .build());

        assertThat(client.lookupMetadata("t", "a")).isEmpty();
    }

    @Test
    @DisplayName("lookupMetadata: 타임아웃/IO 실패도 empty")
    void lookup_ioException_returnsEmpty() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), MockRestRequestMatchers.requestTo(Matchers.any(String.class)))
                .andRespond(withException(new IOException("connection reset")));
        final MusicBrainzSongMetadataLookupClient client = new MusicBrainzSongMetadataLookupClient(PROPERTIES, builder
                .build());

        assertThat(client.lookupMetadata("t", "a")).isEmpty();
    }

    @Test
    @DisplayName("lookupMetadata: 응답 body 가 null(204) 이면 empty")
    void lookup_nullBody_returnsEmpty() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), MockRestRequestMatchers.requestTo(Matchers.any(String.class)))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        final MusicBrainzSongMetadataLookupClient client = new MusicBrainzSongMetadataLookupClient(PROPERTIES, builder
                .build());

        assertThat(client.lookupMetadata("t", "a")).isEmpty();
    }

    @Test
    @DisplayName("lookupMetadata: title 또는 artist 가 null/blank 이면 호출 없이 empty")
    void lookup_blankInput_returnsEmptyWithoutCall() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        final MusicBrainzSongMetadataLookupClient client = new MusicBrainzSongMetadataLookupClient(PROPERTIES, builder
                .build());

        assertThat(client.lookupMetadata(null, "a")).isEmpty();
        assertThat(client.lookupMetadata("", "a")).isEmpty();
        assertThat(client.lookupMetadata("t", null)).isEmpty();
        assertThat(client.lookupMetadata("t", "  ")).isEmpty();
        server.verify();
    }
}
