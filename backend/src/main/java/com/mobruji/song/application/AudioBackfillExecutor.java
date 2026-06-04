package com.mobruji.song.application;

import java.util.List;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.mobruji.song.domain.Song;

import lombok.RequiredArgsConstructor;

/**
 * audio backfill on-demand 트리거의 비동기 실행 경계 — 이슈 #1757.
 *
 * <p>곡당 ytsearch 자동매칭 + librosa 분석이 수 분 걸려 HTTP 응답 스레드를 막을 수 없으므로 별도 빈의
 * {@code @Async} 메서드로 분리한다(같은 빈 내부 호출은 프록시가 적용되지 않으므로 호출자
 * {@link AudioBackfillOnDemandService} 와 빈을 분리). 실제 분석/적용 로직은
 * {@link SongAudioBackfillCommand#runBackfill(List, double)} 을 그대로 재사용한다 — URL 없는 임포트 곡의
 * ytsearch 자동매칭은 {@code runBackfill → analyzeByMetadata → analyze.py} 경로에 이미 포함된다.
 */
@Component
@RequiredArgsConstructor
public class AudioBackfillExecutor {

    private final SongAudioBackfillCommand backfillCommand;

    @Async
    public void runAsync(final List<Song> songs) {
        backfillCommand.runBackfill(songs, SongAudioBackfillCommand.DEFAULT_CONFIDENCE_THRESHOLD);
    }
}
