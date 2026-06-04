package com.mobruji.song.application;

import java.util.List;
import java.util.OptionalInt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

import lombok.RequiredArgsConstructor;

/**
 * audio backfill on-demand admin 트리거 오케스트레이션 — 이슈 #1757.
 *
 * <p>정기 스케줄러({@link AudioAnalysisScheduledBackfill}, 주1회) 와 별개로 운영자가 즉시 음역대 미보유 곡을
 * 채우고 싶을 때 쓰는 진입점. 컨트롤러는 레포지토리에 직접 접근할 수 없으므로(계층 경계) 후보 selection 을 본
 * 서비스가 담당한다. 곡당 ytsearch 자동매칭 + librosa 분석이 수 분 걸리므로 실제 backfill 은
 * {@link AudioBackfillExecutor} 의 {@code @Async} 경계에서 비동기로 돌리고, 트리거는 즉시 선택 집계만 응답한다.
 *
 * <p>{@code dryRun=true}(기본) 는 분석/적용 없이 후보 집계만 미리 본다 — 운영자가 처리량을 가늠하고 실제 실행을
 * 결정하는 안전한 preview 경로.
 */
@Service
@RequiredArgsConstructor
public class AudioBackfillOnDemandService {

    /**
     * 후보 target — 음역대 미보유 곡({@code lowMidi}/{@code highMidi} 중 하나라도 NULL). 추천 풀에서 빠진
     * 임포트 곡을 정밀 타겟한다(기본). {@link SongRepository#findMissingVocalRange()} 결과.
     */
    public static final String TARGET_MISSING_RANGE = "missing-range";

    /**
     * 후보 target — 신뢰도/출처 기반 넓은 후보({@link SongRepository#findCandidatesForBackfill(double)}).
     * 이미 음역대가 있는 곡도 재분석 대상에 포함하므로 정기 스케줄러와 동일한 universe 를 즉시 돌릴 때 쓴다.
     */
    public static final String TARGET_CANDIDATES = "candidates";

    private static final Logger LOG = LoggerFactory.getLogger(AudioBackfillOnDemandService.class);

    private final SongRepository songRepository;
    private final AudioBackfillExecutor backfillExecutor;

    /**
     * 후보를 골라 집계하고, {@code dryRun=false} 이면 비동기 backfill 을 시작한다.
     *
     * @param target {@link #TARGET_MISSING_RANGE} 또는 {@link #TARGET_CANDIDATES} (그 외 값은 missing-range 로 취급)
     * @param limit  처리 상한 (양수만 의미 — 미지정/0/음수는 전체 후보)
     * @param dryRun true 면 분석/적용 없이 집계만 반환
     * @return 트리거 집계
     */
    public TriggerResult trigger(final String target, final OptionalInt limit, final boolean dryRun) {
        final List<Song> candidates = findCandidates(target);
        final List<Song> selected = limit.isPresent()
                ? candidates.stream().limit(limit.getAsInt()).toList()
                : candidates;
        LOG.info(
                "audio backfill on-demand trigger target={} candidates={} limit={} selected={} dryRun={}",
                target, candidates.size(), limit.isPresent() ? limit.getAsInt() : -1,
                selected.size(), dryRun);
        if (dryRun || selected.isEmpty()) {
            return new TriggerResult(candidates.size(), selected.size(), dryRun, false);
        }
        backfillExecutor.runAsync(selected);
        return new TriggerResult(candidates.size(), selected.size(), false, true);
    }

    private List<Song> findCandidates(final String target) {
        if (TARGET_CANDIDATES.equalsIgnoreCase(target)) {
            return songRepository.findCandidatesForBackfill(
                    SongAudioBackfillCommand.DEFAULT_CONFIDENCE_THRESHOLD);
        }
        return songRepository.findMissingVocalRange();
    }

    /**
     * 트리거 집계.
     *
     * @param candidates 후보 곡 수 (limit 적용 전, target 별 selective query 결과)
     * @param selected   이번 트리거 처리 대상 곡 수 (limit 적용 후)
     * @param dryRun     미적용 미리보기였는지
     * @param started    비동기 backfill 이 실제로 시작됐는지
     */
    public record TriggerResult(
            int candidates,
            int selected,
            boolean dryRun,
            boolean started
    ) {
    }
}
