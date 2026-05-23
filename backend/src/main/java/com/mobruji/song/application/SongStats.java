package com.mobruji.song.application;

import java.time.Instant;
import java.util.Map;

import com.mobruji.song.domain.MetadataSource;

/**
 * Admin 통계 application 모델 — {@link SongStatsService#getStats()} 가 반환한다.
 *
 * <p>spec rev 14 후속(#208/#212): {@code GET /api/v1/songs/stats} 가 본 모델을 DTO 로 매핑한다.
 *
 * @param total                    전체 곡 수
 * @param byMetadataSource         {@link MetadataSource} 별 곡 수 — 0 건인 source 도 enum 전체에 대해 0 으로 채운다
 * @param avgConfidence            평균 metadataConfidence (0.0~1.0). 곡 0 건이면 {@code 0.0}
 * @param lastBackfillAt           마지막 audio backfill batch 완료 시각 — 미실행/재기동 후 미실행 시 {@code null}
 * @param lastAlbumCoverBackfillAt 마지막 album cover backfill batch 완료 시각 — 미실행/재기동 후 미실행 시 {@code null} (이슈 #863)
 */
public record SongStats(
        long total,
        Map<MetadataSource, Long> byMetadataSource,
        double avgConfidence,
        Instant lastBackfillAt,
        Instant lastAlbumCoverBackfillAt
) {
}
