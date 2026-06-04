package com.mobruji.song.application.albumcover;

import java.util.List;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.mobruji.song.domain.Song;

import lombok.RequiredArgsConstructor;

/**
 * album cover backfill on-demand 트리거의 비동기 실행 경계 — 이슈 #1766. audio backfill
 * ({@code AudioBackfillExecutor}, #1757) 패턴 미러.
 *
 * <p>곡당 iTunes/Cover Art Archive lookup + throttle sleep 이 누적돼 HTTP 응답 스레드를 막을 수 없으므로 별도
 * 빈의 {@code @Async} 메서드로 분리한다(같은 빈 내부 호출은 프록시가 적용되지 않으므로 호출자
 * {@link AlbumCoverBackfillOnDemandService} 와 빈을 분리). 실제 lookup/적용 로직은
 * {@link AlbumCoverBackfillCommand#runBackfill(List)} 을 그대로 재사용한다.
 */
@Component
@RequiredArgsConstructor
public class AlbumCoverBackfillExecutor {

    private final AlbumCoverBackfillCommand backfillCommand;

    @Async
    public void runAsync(final List<Song> songs) {
        backfillCommand.runBackfill(songs);
    }
}
