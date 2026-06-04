package com.mobruji.song.api.dto;

import com.mobruji.song.application.catalogimport.CatalogBrowseImportCommand.BrowseSummary;

/**
 * MusicBrainz browse 기반 카탈로그 대량 임포트 admin 트리거 응답 — spec {@code song-catalog-expansion.md} (#1705).
 *
 * @param artistsProcessed browse 시도한 아티스트 수
 * @param recordingsSeen   외부에서 받아 검토한 recording 총 수
 * @param inserted         신규 적재(dryRun 시 적재 가능) 곡 수
 * @param skipped          멱등 중복으로 건너뛴 곡 수
 * @param failed           제목/아티스트 누락·저장 오류로 적재 못 한 곡 수
 * @param aborted          503 rate limit 으로 batch 가 중단됐는지
 * @param elapsedMs        전체 소요 (ms)
 */
public record CatalogBrowseImportResponse(
        int artistsProcessed,
        int recordingsSeen,
        int inserted,
        int skipped,
        int failed,
        boolean aborted,
        long elapsedMs
) {

    public static CatalogBrowseImportResponse from(final BrowseSummary summary) {
        return new CatalogBrowseImportResponse(
                summary.artistsProcessed(),
                summary.recordingsSeen(),
                summary.inserted(),
                summary.skipped(),
                summary.failed(),
                summary.aborted(),
                summary.elapsedMs());
    }
}
