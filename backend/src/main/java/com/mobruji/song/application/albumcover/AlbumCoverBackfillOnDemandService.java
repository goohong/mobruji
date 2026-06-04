package com.mobruji.song.application.albumcover;

import java.util.List;
import java.util.OptionalInt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

import lombok.RequiredArgsConstructor;

/**
 * album cover backfill on-demand admin 트리거 오케스트레이션 — 이슈 #1766. audio backfill
 * ({@code AudioBackfillOnDemandService}, #1757) 패턴 미러.
 *
 * <p>정기 스케줄러({@link AlbumCoverScheduledBackfill}, 주1회 일 04:30) 와 별개로 운영자가 즉시 커버 미보유 곡을
 * 채우고 싶을 때 쓰는 진입점. 컨트롤러는 레포지토리에 직접 접근할 수 없으므로(계층 경계) 후보 selection 을 본
 * 서비스가 담당한다. 곡당 iTunes/Cover Art Archive lookup + throttle sleep 이 누적되므로 실제 backfill 은
 * {@link AlbumCoverBackfillExecutor} 의 {@code @Async} 경계에서 비동기로 돌리고, 트리거는 즉시 선택 집계만 응답한다.
 *
 * <p>{@code dryRun=true}(기본) 는 lookup/적용 없이 후보 집계만 미리 본다 — 운영자가 처리량을 가늠하고 실제 실행을
 * 결정하는 안전한 preview 경로.
 */
@Service
@RequiredArgsConstructor
public class AlbumCoverBackfillOnDemandService {

    private static final Logger LOG = LoggerFactory.getLogger(AlbumCoverBackfillOnDemandService.class);

    private final SongRepository songRepository;
    private final AlbumCoverBackfillExecutor backfillExecutor;

    /**
     * 커버 미보유 후보({@code albumCoverUrl IS NULL})를 골라 집계하고, {@code dryRun=false} 이면 비동기 backfill 을
     * 시작한다.
     *
     * @param limit  처리 상한 (양수만 의미 — 미지정/0/음수는 전체 후보)
     * @param dryRun true 면 lookup/적용 없이 집계만 반환
     * @return 트리거 집계
     */
    public TriggerResult trigger(final OptionalInt limit, final boolean dryRun) {
        final List<Song> candidates = songRepository.findMissingAlbumCover();
        final List<Song> selected = limit.isPresent()
                ? candidates.stream().limit(limit.getAsInt()).toList()
                : candidates;
        LOG.info(
                "album cover backfill on-demand trigger candidates={} limit={} selected={} dryRun={}",
                candidates.size(), limit.isPresent() ? limit.getAsInt() : -1, selected.size(), dryRun);
        if (dryRun || selected.isEmpty()) {
            return new TriggerResult(candidates.size(), selected.size(), dryRun, false);
        }
        backfillExecutor.runAsync(selected);
        return new TriggerResult(candidates.size(), selected.size(), false, true);
    }

    /**
     * 트리거 집계.
     *
     * @param candidates 후보 곡 수 (limit 적용 전, {@code albumCoverUrl IS NULL} 곡)
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
