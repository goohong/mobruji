package com.mobruji.song.application.catalogimport;

import java.util.List;

/**
 * 아티스트 단위로 외부 CC0 출처에서 recording 후보를 browse 하는 추상 — spec {@code song-catalog-expansion.md}
 * §5-3 (#1705). 1차 구현은 {@link MusicBrainzBrowseClient}.
 *
 * <p>graceful 계약 — 외부 오류/무매칭/parse 실패 시 빈 목록을 반환하고 예외를 던지지 않는다 (페이지 단위 격리).
 * 단, 503 backoff 소진은 {@link CatalogBrowseRateLimitException} 으로 전파해 batch 전체 중단을 신호한다 (rate limit 보호).
 */
public interface CatalogBrowseClient {

    /**
     * 아티스트 표기로 recording 을 검색해 한 페이지를 가져온다.
     *
     * @param artistName 한국 대중가요 아티스트 표기 (수기 큐레이션 시드)
     * @param limit      페이지 크기 (top N)
     * @param offset     페이징 offset (0-based)
     * @return 발견한 recording 목록. 무매칭/오류면 빈 목록.
     * @throws CatalogBrowseRateLimitException 503 backoff 재시도를 모두 소진한 경우
     */
    List<BrowsedRecording> browseByArtist(String artistName, int limit, int offset);
}
