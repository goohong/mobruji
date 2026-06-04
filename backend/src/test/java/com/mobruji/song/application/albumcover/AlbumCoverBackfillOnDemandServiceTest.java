package com.mobruji.song.application.albumcover;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.OptionalInt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.mobruji.song.application.albumcover.AlbumCoverBackfillOnDemandService.TriggerResult;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

/**
 * {@link AlbumCoverBackfillOnDemandService} 단위 테스트 — 후보 selection / limit / dryRun 분기 / 비동기 위임을
 * {@link SongRepository} 와 {@link AlbumCoverBackfillExecutor} mock 으로 검증한다.
 */
class AlbumCoverBackfillOnDemandServiceTest {

    private static Song missingCoverSong(final String title) {
        return Song.builder()
                .title(title)
                .artist("artist-" + title)
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
    }

    @Test
    @DisplayName("dryRun=true: 후보 집계만 반환하고 비동기 실행은 트리거하지 않는다")
    void dryRun_countsOnly_doesNotRun() {
        // given
        final SongRepository repo = mock(SongRepository.class);
        final AlbumCoverBackfillExecutor executor = mock(AlbumCoverBackfillExecutor.class);
        when(repo.findMissingAlbumCover())
                .thenReturn(List.of(missingCoverSong("a"), missingCoverSong("b")));
        final AlbumCoverBackfillOnDemandService service = new AlbumCoverBackfillOnDemandService(repo, executor);

        // when
        final TriggerResult result = service.trigger(OptionalInt.empty(), true);

        // then
        assertThat(result.candidates()).isEqualTo(2);
        assertThat(result.selected()).isEqualTo(2);
        assertThat(result.dryRun()).isTrue();
        assertThat(result.started()).isFalse();
        verify(executor, never()).runAsync(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    @DisplayName("dryRun=false + limit: 상한만큼 잘라 비동기 backfill 을 시작한다")
    void run_withLimit_startsAsyncOnSelected() {
        // given
        final SongRepository repo = mock(SongRepository.class);
        final AlbumCoverBackfillExecutor executor = mock(AlbumCoverBackfillExecutor.class);
        when(repo.findMissingAlbumCover()).thenReturn(List.of(
                missingCoverSong("a"), missingCoverSong("b"), missingCoverSong("c")));
        final AlbumCoverBackfillOnDemandService service = new AlbumCoverBackfillOnDemandService(repo, executor);

        // when
        final TriggerResult result = service.trigger(OptionalInt.of(2), false);

        // then
        assertThat(result.candidates()).isEqualTo(3);
        assertThat(result.selected()).isEqualTo(2);
        assertThat(result.dryRun()).isFalse();
        assertThat(result.started()).isTrue();

        @SuppressWarnings("unchecked") final ArgumentCaptor<List<Song>> captor = ArgumentCaptor.forClass(List.class);
        verify(executor).runAsync(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
    }

    @Test
    @DisplayName("dryRun=false + 후보 0건: 비동기 실행 없이 started=false")
    void run_noCandidates_doesNotStart() {
        // given
        final SongRepository repo = mock(SongRepository.class);
        final AlbumCoverBackfillExecutor executor = mock(AlbumCoverBackfillExecutor.class);
        when(repo.findMissingAlbumCover()).thenReturn(List.of());
        final AlbumCoverBackfillOnDemandService service = new AlbumCoverBackfillOnDemandService(repo, executor);

        // when
        final TriggerResult result = service.trigger(OptionalInt.empty(), false);

        // then
        assertThat(result.candidates()).isZero();
        assertThat(result.selected()).isZero();
        assertThat(result.started()).isFalse();
        verify(executor, never()).runAsync(org.mockito.ArgumentMatchers.anyList());
    }
}
