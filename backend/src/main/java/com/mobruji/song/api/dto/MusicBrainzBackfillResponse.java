package com.mobruji.song.api.dto;

import com.mobruji.song.application.musicbrainz.MusicBrainzBackfillCommand.BackfillSummary;

/**
 * MusicBrainz backfill admin 트리거 응답 — spec {@code musicbrainz-integration.md} §5-2.
 *
 * @param processed 조회·시도한 곡 수
 * @param matched   매칭 채택(dryRun 시 채택 가능) 곡 수
 * @param lowScore  top-hit score 가 임계 미만이라 skip 한 곡 수
 * @param notFound  검색 무매칭 곡 수
 * @param failed    오류/충돌로 적용 못 한 곡 수
 * @param aborted   503 rate limit 으로 batch 가 중단됐는지
 * @param elapsedMs 전체 소요 (ms)
 */
public record MusicBrainzBackfillResponse(
        int processed,
        int matched,
        int lowScore,
        int notFound,
        int failed,
        boolean aborted,
        long elapsedMs
) {

    public static MusicBrainzBackfillResponse from(final BackfillSummary summary) {
        return new MusicBrainzBackfillResponse(
                summary.processed(),
                summary.matched(),
                summary.lowScore(),
                summary.notFound(),
                summary.failed(),
                summary.aborted(),
                summary.elapsedMs());
    }
}
