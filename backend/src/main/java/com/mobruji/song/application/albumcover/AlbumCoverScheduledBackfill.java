package com.mobruji.song.application.albumcover;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

/**
 * 정기 앨범 커버 backfill batch — 이슈 #322 PR B.
 *
 * <p>운영 안전:
 * <ul>
 * <li>{@code @Profile("prod")} 로 prod 프로파일에서만 활성. local/test 미등록.</li>
 * <li>cron {@code 0 30 4 * * SUN} (KST) — 일요일 새벽 4:30 (audio analysis batch 4:00 직후, 충돌 회피).</li>
 * <li>대상: {@link SongRepository#findMissingAlbumCover()} 로 albumCoverUrl=null 곡만 selective 조회.</li>
 * <li>곡 단위 실패는 {@link AlbumCoverBackfillCommand#runBackfill(List)} 내부에서 격리되어 batch 전체 중단 없음.</li>
 * </ul>
 *
 * <p>결정성 영향 없음 — UX 표시 전용 (ADR 0010 정합).
 */
@Component
@Profile("prod")
public class AlbumCoverScheduledBackfill {

    private static final Logger LOG = LoggerFactory.getLogger(AlbumCoverScheduledBackfill.class);

    private final SongRepository songRepository;
    private final AlbumCoverBackfillCommand backfillCommand;

    public AlbumCoverScheduledBackfill(
            final SongRepository songRepository,
            final AlbumCoverBackfillCommand backfillCommand) {
        this.songRepository = songRepository;
        this.backfillCommand = backfillCommand;
    }

    /**
     * 매주 일요일 새벽 4:30 (Asia/Seoul). audio analysis backfill (4:00) 과 30분 간격 두어 외부 호출 부하 분산.
     */
    @Scheduled(cron = "0 30 4 * * SUN", zone = "Asia/Seoul")
    public void runScheduledBackfill() {
        final List<Song> targets = selectTargets();
        if (targets.isEmpty()) {
            LOG.info("album cover scheduled backfill: no targets, skipped");
            return;
        }
        LOG.info("album cover scheduled backfill: start targets={}", targets.size());
        final AlbumCoverBackfillCommand.BackfillSummary summary = backfillCommand.runBackfill(targets);
        LOG.info(
                "album cover scheduled backfill: done analyzed={} matched={} updated={} missed={}",
                summary.analyzed(), summary.matched(), summary.updated(), summary.missed());
    }

    /** 대상 selection — {@link SongRepository#findMissingAlbumCover()} 위임. */
    List<Song> selectTargets() {
        return songRepository.findMissingAlbumCover();
    }
}
