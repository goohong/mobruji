package com.mobruji.song.application.catalogimport;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * MusicBrainz browse 임포트의 입력이 되는 한국 대중가요 아티스트 시드 로더 — spec
 * {@code song-catalog-expansion.md} §2-1 (#1705).
 *
 * <p>{@code classpath:/korean-artists-seed.json} (아티스트 표기 문자열 배열) 를 읽는다. 이 목록은 운영자가
 * 차트를 수기 참고해 정리한 합법 경로다 (자동 크롤 아님, #1640). {@link CatalogBrowseImportCommand} 가 각
 * 아티스트를 browse 해 recording 을 대량 발견한다.
 */
@Component
public class KoreanArtistSeed {

    static final String SEED_PATH = "korean-artists-seed.json";

    private static final Logger LOG = LoggerFactory.getLogger(KoreanArtistSeed.class);

    private final ObjectMapper objectMapper;

    public KoreanArtistSeed(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 아티스트 시드 목록을 읽는다. 파일 부재 시 빈 목록 (배치는 no-op).
     */
    public List<String> load() {
        final ClassPathResource resource = new ClassPathResource(SEED_PATH);
        if (!resource.exists()) {
            LOG.warn("korean artist seed not found at classpath:/{}, returning empty", SEED_PATH);
            return List.of();
        }
        try (InputStream inputStream = resource.getInputStream()) {
            return objectMapper.readValue(
                    inputStream,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (final IOException e) {
            throw new UncheckedIOException("failed to read " + SEED_PATH, e);
        }
    }
}
