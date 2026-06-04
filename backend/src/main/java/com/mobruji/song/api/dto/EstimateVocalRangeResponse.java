package com.mobruji.song.api.dto;

import com.mobruji.song.application.SongVocalRangeEstimateCommand.EstimateSummary;

/**
 * 음역대 메타추정 on-demand admin 트리거 응답 — 이슈 #1788.
 *
 * @param scanned            이번 트리거가 훑은 미보유 곡 수 (limit 적용 후)
 * @param applied            메타 추정 음역대를 채워 ESTIMATED 로 적용한 곡 수
 * @param skippedUnestimable 키 UNKNOWN 등으로 추정 불가해 건너뛴 곡 수
 * @param skippedNotApplied  추정값이 도메인 적용 조건을 못 넘어 건너뛴 곡 수
 */
public record EstimateVocalRangeResponse(
        int scanned,
        int applied,
        int skippedUnestimable,
        int skippedNotApplied
) {

    public static EstimateVocalRangeResponse from(final EstimateSummary summary) {
        return new EstimateVocalRangeResponse(
                summary.scanned(),
                summary.applied(),
                summary.skippedUnestimable(),
                summary.skippedNotApplied());
    }
}
