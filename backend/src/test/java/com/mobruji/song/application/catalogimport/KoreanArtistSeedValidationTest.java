package com.mobruji.song.application.catalogimport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 번들된 {@code korean-artists-seed.json} 아티스트 시드의 형식/중복/규모 회귀 가드 — spec
 * {@code song-catalog-expansion.md} (#1705). 시드가 비거나 중복으로 조용히 회귀하면 browse 발견량이 무너지므로
 * 빌드에서 잡는다. 패턴: {@code SongImportCandidatesValidationTest} 동일.
 */
class KoreanArtistSeedValidationTest {

    /** 시드가 이 아래로 줄면 대량 발견 회귀로 본다. */
    private static final int MINIMUM_SEED_SIZE = 30;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static List<String> loadBundledSeed() throws Exception {
        try (InputStream inputStream = new ClassPathResource(KoreanArtistSeed.SEED_PATH).getInputStream()) {
            return OBJECT_MAPPER.readValue(
                    inputStream,
                    OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
        }
    }

    @Test
    @DisplayName("번들 시드 JSON: 파싱 가능 + 최소 규모 이상")
    void bundledSeed_parseableAndAboveMinimumSize() throws Exception {
        final List<String> seed = loadBundledSeed();

        assertThat(seed).hasSizeGreaterThanOrEqualTo(MINIMUM_SEED_SIZE);
    }

    @Test
    @DisplayName("번들 시드 JSON: 아티스트명 모두 비어있지 않음")
    void bundledSeed_noBlankArtist() throws Exception {
        final List<String> seed = loadBundledSeed();

        assertThat(seed).allSatisfy(artist -> assertThat(artist).isNotBlank());
    }

    @Test
    @DisplayName("번들 시드 JSON: 아티스트명 중복 없음")
    void bundledSeed_noDuplicates() throws Exception {
        final List<String> seed = loadBundledSeed();

        assertThat(seed).doesNotHaveDuplicates();
    }
}
