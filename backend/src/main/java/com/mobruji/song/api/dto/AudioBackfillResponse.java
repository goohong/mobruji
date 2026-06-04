package com.mobruji.song.api.dto;

import com.mobruji.song.application.AudioBackfillOnDemandService.TriggerResult;

/**
 * audio backfill on-demand admin 트리거 응답 — 이슈 #1757.
 *
 * @param candidates 후보 곡 수 (limit 적용 전, target 별 selective query 결과)
 * @param selected   이번 트리거가 처리 대상으로 고른 곡 수 (limit 적용 후)
 * @param dryRun     미적용 미리보기였는지 — true 면 분석/적용 없이 집계만 반환
 * @param started    비동기 backfill 이 실제로 시작됐는지
 */
public record AudioBackfillResponse(
        int candidates,
        int selected,
        boolean dryRun,
        boolean started
) {

    public static AudioBackfillResponse from(final TriggerResult result) {
        return new AudioBackfillResponse(
                result.candidates(), result.selected(), result.dryRun(), result.started());
    }
}
