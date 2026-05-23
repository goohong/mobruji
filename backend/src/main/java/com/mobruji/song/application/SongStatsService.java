package com.mobruji.song.application;

import java.util.EnumMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.mobruji.song.application.albumcover.AlbumCoverBackfillCommand;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.infrastructure.SongRepository;
import com.mobruji.song.infrastructure.SongRepository.MetadataSourceCount;

/**
 * Admin/운영 통계 조회 — SongAudioBackfill 운영 가시성.
 *
 * <p>spec rev 14 후속(#208/#212): metadataSource 분포 / 평균 confidence / 마지막 backfill 시각을 노출.
 * 추천 알고리즘과 무관 — 결정성 회귀 없음.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SongStatsService {

    private final SongRepository songRepository;

    public SongStats getStats() {
        final long total = songRepository.count();
        final Map<MetadataSource, Long> byMetadataSource = new EnumMap<>(MetadataSource.class);
        // 0 건인 source 도 enum 전체에 대해 0 으로 채운다 — FE 가 누락 키를 방어할 필요 없음.
        for (final MetadataSource source : MetadataSource.values()) {
            byMetadataSource.put(source, 0L);
        }
        for (final MetadataSourceCount row : songRepository.countByMetadataSource()) {
            byMetadataSource.put(row.getMetadataSource(), row.getCount());
        }
        final Double avg = songRepository.findAverageMetadataConfidence();
        final double avgConfidence = avg != null ? avg : 0.0;
        return new SongStats(
                total,
                byMetadataSource,
                avgConfidence,
                SongAudioBackfillCommand.getLastBackfillCompletedAt(),
                AlbumCoverBackfillCommand.getLastBackfillCompletedAt());
    }
}
