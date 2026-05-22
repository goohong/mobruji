package com.mobruji.song.application.albumcover;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

/**
 * {@link AlbumCoverScheduledBackfill} 단위 테스트. selective query 결과를 그대로 위임하는지 검증.
 */
@SuppressWarnings("unchecked")
class AlbumCoverScheduledBackfillTest {

    private static Song seed(final String title) {
        return Song.builder()
                .title(title).artist("artist-" + title)
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
    }

    @Test
    @DisplayName("runScheduledBackfill: selective query 결과를 그대로 backfillCommand 에 위임")
    void runScheduledBackfill_delegatesQueryResult() {
        final Song s1 = seed("a");
        final Song s2 = seed("b");
        final SongRepository repository = mock(SongRepository.class);
        when(repository.findMissingAlbumCover()).thenReturn(List.of(s1, s2));
        final AlbumCoverBackfillCommand backfillCommand = mock(AlbumCoverBackfillCommand.class);
        when(backfillCommand.runBackfill(any(List.class)))
                .thenReturn(new AlbumCoverBackfillCommand.BackfillSummary(2, 1, 1, 1));

        final AlbumCoverScheduledBackfill scheduler = new AlbumCoverScheduledBackfill(repository, backfillCommand);
        scheduler.runScheduledBackfill();

        verify(backfillCommand).runBackfill(List.of(s1, s2));
    }

    @Test
    @DisplayName("runScheduledBackfill: 대상 0건이면 backfillCommand 호출 없이 종료")
    void runScheduledBackfill_noTargets_skips() {
        final SongRepository repository = mock(SongRepository.class);
        when(repository.findMissingAlbumCover()).thenReturn(List.of());
        final AlbumCoverBackfillCommand backfillCommand = mock(AlbumCoverBackfillCommand.class);

        final AlbumCoverScheduledBackfill scheduler = new AlbumCoverScheduledBackfill(repository, backfillCommand);
        scheduler.runScheduledBackfill();

        verify(backfillCommand, never()).runBackfill(any(List.class));
    }

    @Test
    @DisplayName("selectTargets: SongRepository#findMissingAlbumCover 를 그대로 위임 호출")
    void selectTargets_delegatesToRepository() {
        final Song s1 = seed("only");
        final SongRepository repository = mock(SongRepository.class);
        when(repository.findMissingAlbumCover()).thenReturn(List.of(s1));

        final AlbumCoverScheduledBackfill scheduler = new AlbumCoverScheduledBackfill(
                repository, mock(AlbumCoverBackfillCommand.class));

        final List<Song> targets = scheduler.selectTargets();
        assertThat(targets).containsExactly(s1);
        verify(repository).findMissingAlbumCover();
    }
}
