package com.mobruji.song.api.dto;

import java.time.Instant;
import java.util.Map;

import com.mobruji.song.application.SongStats;
import com.mobruji.song.domain.MetadataSource;

/**
 * Admin 통계 응답 — {@code GET /api/v1/songs/stats}.
 *
 * <p>spec rev 14 후속(#208/#212): SongAudioBackfill 운영 가시성. metadataSource 분포와 평균 confidence,
 * 마지막 backfill 시각을 노출한다.
 *
 * @param total            전체 곡 수
 * @param byMetadataSource {@link MetadataSource} 별 곡 수 — 0 건인 source 도 enum 전체에 대해 0 으로 채운다
 * @param avgConfidence    평균 metadataConfidence (0.0~1.0). 곡 0 건이면 {@code 0.0}
 * @param lastBackfillAt   마지막 backfill batch 완료 시각 — 미실행/재기동 후 미실행 시 {@code null}
 */
public record SongStatsResponse(
        long total,
        Map<MetadataSource, Long> byMetadataSource,
        double avgConfidence,
        Instant lastBackfillAt
) {

    public static SongStatsResponse from(final SongStats stats) {
        return new SongStatsResponse(
                stats.total(),
                stats.byMetadataSource(),
                stats.avgConfidence(),
                stats.lastBackfillAt());
    }
}
