package com.mobruji.song.application.albumcover;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;

/**
 * {@link AlbumCoverBackfillExecutor} 단위 테스트 — 비동기 경계가 {@link AlbumCoverBackfillCommand#runBackfill(List)}
 * 에 선택 곡을 그대로 위임하는지 검증한다. (실제 lookup/적용은 {@link AlbumCoverBackfillCommand} 테스트가 커버)
 */
class AlbumCoverBackfillExecutorTest {

    @Test
    @DisplayName("runAsync: 선택 곡을 runBackfill 에 그대로 위임한다")
    void runAsync_delegatesToCommand() {
        // given
        final AlbumCoverBackfillCommand command = mock(AlbumCoverBackfillCommand.class);
        final AlbumCoverBackfillExecutor executor = new AlbumCoverBackfillExecutor(command);
        final List<Song> songs = List.of(Song.builder()
                .title("커버미보유").artist("가수")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build());

        // when
        executor.runAsync(songs);

        // then
        verify(command).runBackfill(songs);
    }
}
