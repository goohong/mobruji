package com.mobruji.song.application.musicbrainz;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 정기 MusicBrainz backfill batch — spec {@code musicbrainz-integration.md} PR B (#268).
 *
 * <p>운영 안전:
 * <ul>
 * <li>{@code @Profile("prod")} 로 prod 프로파일에서만 Bean 등록. local/test 미등록.</li>
 * <li>cron 은 {@code musicbrainz.backfill.schedule-cron} (KST) — 기본 매일 04:00 (audio/album cover batch 와 분리).</li>
 * <li>{@code musicbrainz.backfill.enabled=false} (기본) 면 트리거돼도 no-op — User-Agent contact 미설정 운영
 * 환경에서 약관 위반 호출을 막는다 (운영자가 명시적으로 enable).</li>
 * <li>503 rate limit 소진 시 {@link MusicBrainzBackfillCommand} 가 batch 전체를 중단한다.</li>
 * </ul>
 *
 * <p>결정성 영향 없음 — 음역대/key/tempo 미보강이라 추천 점수 입력과 무관 (ADR 0010 정합).
 */
@Component
@Profile("prod")
public class MusicBrainzBackfillScheduledJob {

    private static final Logger LOG = LoggerFactory.getLogger(MusicBrainzBackfillScheduledJob.class);

    private final MusicBrainzBackfillCommand backfillCommand;
    private final MusicBrainzProperties properties;

    public MusicBrainzBackfillScheduledJob(
            final MusicBrainzBackfillCommand backfillCommand,
            final MusicBrainzProperties properties) {
        this.backfillCommand = backfillCommand;
        this.properties = properties;
    }

    @Scheduled(cron = "${musicbrainz.backfill.schedule-cron}", zone = "Asia/Seoul")
    public void runScheduledBackfill() {
        if (!properties.backfill().enabled()) {
            LOG.info("musicbrainz scheduled backfill disabled (musicbrainz.backfill.enabled=false), skipped");
            return;
        }
        final MusicBrainzBackfillCommand.BackfillSummary summary = backfillCommand.runBackfill();
        LOG.info(
                "musicbrainz scheduled backfill: processed={} matched={} lowScore={} notFound={} "
                        + "failed={} aborted={}",
                summary.processed(), summary.matched(), summary.lowScore(),
                summary.notFound(), summary.failed(), summary.aborted());
    }
}
