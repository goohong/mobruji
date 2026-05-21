package com.mobruji.song.application;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

/**
 * 정기 audio analysis backfill batch — 매주 일요일 새벽 4시 (KST) 에 신규 곡 / 낮은 신뢰도 곡을 분석.
 *
 * <p>spec: {@code docs/features/audio-tooling-bootstrap.md} PR D — 시드 backfill ({@link SongAudioBackfillCommand})
 * 이 일회성이라면, 본 클래스는 운영 중 추가되는 곡 / 1차 분석 실패 곡을 점진적으로 audio 분석값으로 정확화하는 정기 batch 다.
 *
 * <p>운영 안전:
 *
 * <ul>
 * <li>{@code @Profile("prod")} 로 prod 프로파일에서만 활성. local/test 에서는 빈 자체가 등록되지 않는다.</li>
 * <li>cron {@code 0 0 4 * * SUN} (KST) — 일요일 새벽 4시. 사용자 트래픽 거의 없는 시간대.</li>
 * <li>대상: {@code metadataSource != AUDIO_ANALYSIS} 인 곡 — 도메인에 별도 confidence 컬럼은 없으므로,
 * "audio 분석으로 갱신된 적 없는 곡" (수기 시드 + 외부 출처 + 미분석 신규 곡 포함) 을 selective 하게 backfill 한다.
 * spec 의 "metadataConfidence &lt; 0.6" 의도와 정합.</li>
 * <li>곡 단위 실패는 {@link SongAudioBackfillCommand#runBackfill(List, double)} 내부에서 격리되어 전체 batch 가 중단되지 않는다.</li>
 * </ul>
 *
 * <p>결정성 영향 없음 — 추천 알고리즘 입력 데이터(lowMidi/highMidi)만 정확해질 뿐, 알고리즘 코드 변경 없음 (ADR 0010).
 */
@Component
@Profile("prod")
public class AudioAnalysisScheduledBackfill {

    /**
     * 적용 임계 confidence — {@link SongAudioBackfillCommand#DEFAULT_CONFIDENCE_THRESHOLD} 와 동일 0.6.
     * 임계 미달은 기존 값을 보존한다.
     */
    static final double CONFIDENCE_THRESHOLD = SongAudioBackfillCommand.DEFAULT_CONFIDENCE_THRESHOLD;

    private static final Logger LOG = LoggerFactory.getLogger(AudioAnalysisScheduledBackfill.class);

    private final SongRepository songRepository;
    private final SongAudioBackfillCommand backfillCommand;

    public AudioAnalysisScheduledBackfill(
            final SongRepository songRepository,
            final SongAudioBackfillCommand backfillCommand) {
        this.songRepository = songRepository;
        this.backfillCommand = backfillCommand;
    }

    /**
     * 매주 일요일 새벽 4시 (Asia/Seoul) 에 미분석/낮은 신뢰도 곡만 골라 backfill 실행.
     *
     * <p>cron 표현식: {@code 초 분 시 일 월 요일} — Spring 6-field. zone 은 Asia/Seoul 고정.
     */
    @Scheduled(cron = "0 0 4 * * SUN", zone = "Asia/Seoul")
    public void runScheduledBackfill() {
        final List<Song> targets = selectTargets();
        if (targets.isEmpty()) {
            LOG.info("audio scheduled backfill: no targets, skipped");
            return;
        }
        LOG.info("audio scheduled backfill: start targets={}", targets.size());
        final SongAudioBackfillCommand.BackfillSummary summary = backfillCommand.runBackfill(targets,
                CONFIDENCE_THRESHOLD);
        LOG.info(
                "audio scheduled backfill: done analyzed={} successful={} updated={} skipped={} failed={}",
                summary.analyzed(), summary.successful(), summary.updated(),
                summary.skippedLowConfidence(), summary.failed());
    }

    /**
     * 분석 대상 selection — {@code metadataSource != AUDIO_ANALYSIS} 인 곡. v0.x 단축형으로
     * 별도 confidence 컬럼은 아직 없어 source 기반으로 대체한다 (spec 의 metadataConfidence &lt; 0.6 의도).
     *
     * <p>곡 수가 많아지면 {@link SongRepository} 에 selective query 메서드를 추가하는 것이 적절하나,
     * 본 PR 범위에서는 in-memory 필터로 단순화한다.
     */
    List<Song> selectTargets() {
        return songRepository.findAll().stream()
                .filter(song -> song.getMetadataSource() != MetadataSource.AUDIO_ANALYSIS)
                .toList();
    }
}
