package com.mobruji.song.api.dto;

import com.mobruji.song.application.albumcover.AlbumCoverBackfillOnDemandService.TriggerResult;

/**
 * album cover backfill on-demand admin 트리거 응답 — 이슈 #1766.
 *
 * @param candidates 후보 곡 수 (limit 적용 전, {@code albumCoverUrl IS NULL} 곡)
 * @param selected   이번 트리거가 처리 대상으로 고른 곡 수 (limit 적용 후)
 * @param dryRun     미적용 미리보기였는지 — true 면 lookup/적용 없이 집계만 반환
 * @param started    비동기 backfill 이 실제로 시작됐는지
 */
public record AlbumCoverBackfillResponse(
        int candidates,
        int selected,
        boolean dryRun,
        boolean started
) {

    public static AlbumCoverBackfillResponse from(final TriggerResult result) {
        return new AlbumCoverBackfillResponse(
                result.candidates(), result.selected(), result.dryRun(), result.started());
    }
}
