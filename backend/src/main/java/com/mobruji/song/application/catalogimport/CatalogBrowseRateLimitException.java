package com.mobruji.song.application.catalogimport;

/**
 * MusicBrainz browse 호출이 503 backoff 재시도를 모두 소진했을 때 던지는 신호 — spec
 * {@code song-catalog-expansion.md} §5-3 (#1705). {@link CatalogBrowseImportCommand} 가 이를 받아 batch 전체를
 * 즉시 중단한다 (rate limit 침해 방지). 곡/페이지 단위 graceful 실패와 구분하기 위한 전용 예외.
 */
public class CatalogBrowseRateLimitException extends RuntimeException {

    public CatalogBrowseRateLimitException(final String message) {
        super(message);
    }
}
