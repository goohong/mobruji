package com.mobruji.song.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;

/**
 * {@link AudioBackfillExecutor} 단위 테스트 — 비동기 경계가 {@link SongAudioBackfillCommand#runBackfill(List, double)}
 * 에 선택 곡과 기본 임계를 그대로 위임하는지 검증한다. (실제 분석/적용은 {@link SongAudioBackfillCommandTest} 가 커버)
 */
class AudioBackfillExecutorTest {

    @Test
    @DisplayName("runAsync: 선택 곡을 기본 confidence 임계로 runBackfill 에 위임한다")
    void runAsync_delegatesToCommand() {
        // given
        final SongAudioBackfillCommand command = mock(SongAudioBackfillCommand.class);
        final AudioBackfillExecutor executor = new AudioBackfillExecutor(command);
        final List<Song> songs = List.of(Song.builder()
                .title("미보유").artist("가수")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build());

        // when
        executor.runAsync(songs);

        // then
        verify(command).runBackfill(songs, SongAudioBackfillCommand.DEFAULT_CONFIDENCE_THRESHOLD);
    }
}
