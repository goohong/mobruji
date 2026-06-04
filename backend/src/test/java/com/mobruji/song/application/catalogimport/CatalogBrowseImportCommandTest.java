package com.mobruji.song.application.catalogimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.mobruji.song.application.catalogimport.CatalogBrowseImportCommand.BrowseSummary;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

/**
 * {@link CatalogBrowseImportCommand} 단위 테스트 — mock client/repository 로 멱등성/페이징/규모 상한/격리/중단 회귀 가드.
 */
class CatalogBrowseImportCommandTest {

    private static CatalogBrowseProperties properties(final int pageSize) {
        return new CatalogBrowseProperties(
                "https://musicbrainz.org/ws/2",
                "mobruji-backend/0.1 (+test)",
                Duration.ofSeconds(5),
                Duration.ZERO,
                Duration.ofSeconds(1),
                3,
                pageSize,
                10,
                50,
                0.3);
    }

    private static BrowsedRecording recording(final String mbId, final String title, final String artist) {
        return new BrowsedRecording(mbId, title, artist, null, 2020, "k-pop");
    }

    @Test
    @DisplayName("runBrowseImport: 신규 recording 은 EXTERNAL_API + UNKNOWN key + mbId/메타로 insert")
    void run_newRecording_insertsWithMetadata() {
        final SongRepository repository = mock(SongRepository.class);
        final CatalogBrowseClient client = mock(CatalogBrowseClient.class);
        when(repository.findByMbId(any())).thenReturn(Optional.empty());
        when(repository.findByIsrc(any())).thenReturn(Optional.empty());
        when(repository.findByTitleAndArtist(any(), any())).thenReturn(Optional.empty());
        when(client.browseByArtist(eq("윤종신"), anyInt(), eq(0)))
                .thenReturn(List.of(new BrowsedRecording("mbid-1", "좋니", "윤종신", "KRA401700001", 2017, "k-pop")));

        final CatalogBrowseImportCommand command = new CatalogBrowseImportCommand(
                repository, client, properties(100));

        final BrowseSummary summary = command.runBrowseImport(List.of("윤종신"), 10, 50, false);

        assertThat(summary.inserted()).isEqualTo(1);
        assertThat(summary.skipped()).isZero();
        assertThat(summary.failed()).isZero();

        final ArgumentCaptor<Song> saved = ArgumentCaptor.forClass(Song.class);
        verify(repository, times(1)).save(saved.capture());
        final Song song = saved.getValue();
        assertThat(song.getMetadataSource()).isEqualTo(MetadataSource.EXTERNAL_API);
        assertThat(song.getKeyOriginal()).isEqualTo(MusicalKey.UNKNOWN);
        assertThat(song.getMbId()).isEqualTo("mbid-1");
        assertThat(song.getIsrc()).isEqualTo("KRA401700001");
        assertThat(song.getReleaseYear()).isEqualTo(2017);
        assertThat(song.getGenre()).isEqualTo("k-pop");
        assertThat(song.getMetadataConfidence()).isEqualTo(0.3);
        assertThat(song.getLowMidi()).isNull();
        assertThat(song.getHighMidi()).isNull();
        assertThat(song.getBpm()).isNull();
    }

    @Test
    @DisplayName("runBrowseImport: 동일 mbId 가 이미 DB 에 있으면 skip")
    void run_existingMbId_skips() {
        final SongRepository repository = mock(SongRepository.class);
        final CatalogBrowseClient client = mock(CatalogBrowseClient.class);
        when(repository.findByMbId("mbid-1")).thenReturn(Optional.of(mock(Song.class)));
        when(client.browseByArtist(eq("윤종신"), anyInt(), eq(0)))
                .thenReturn(List.of(recording("mbid-1", "좋니", "윤종신")));

        final CatalogBrowseImportCommand command = new CatalogBrowseImportCommand(
                repository, client, properties(100));

        final BrowseSummary summary = command.runBrowseImport(List.of("윤종신"), 10, 50, false);

        assertThat(summary.skipped()).isEqualTo(1);
        assertThat(summary.inserted()).isZero();
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("runBrowseImport: 동일 ISRC 가 이미 DB 에 있으면 skip (제목/아티스트 표기 무관)")
    void run_existingIsrc_skips() {
        final SongRepository repository = mock(SongRepository.class);
        final CatalogBrowseClient client = mock(CatalogBrowseClient.class);
        when(repository.findByMbId(any())).thenReturn(Optional.empty());
        when(repository.findByIsrc("KRA401700001")).thenReturn(Optional.of(mock(Song.class)));
        when(client.browseByArtist(eq("윤종신"), anyInt(), eq(0)))
                .thenReturn(List.of(new BrowsedRecording("mbid-1", "좋니", "윤종신", "KRA401700001", 2017, "k-pop")));

        final CatalogBrowseImportCommand command = new CatalogBrowseImportCommand(
                repository, client, properties(100));

        final BrowseSummary summary = command.runBrowseImport(List.of("윤종신"), 10, 50, false);

        assertThat(summary.skipped()).isEqualTo(1);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("runBrowseImport: 동일 (title, artist) 가 이미 DB 에 있으면 skip")
    void run_existingTitleArtist_skips() {
        final SongRepository repository = mock(SongRepository.class);
        final CatalogBrowseClient client = mock(CatalogBrowseClient.class);
        when(repository.findByMbId(any())).thenReturn(Optional.empty());
        when(repository.findByIsrc(any())).thenReturn(Optional.empty());
        when(repository.findByTitleAndArtist("좋니", "윤종신")).thenReturn(Optional.of(mock(Song.class)));
        when(client.browseByArtist(eq("윤종신"), anyInt(), eq(0)))
                .thenReturn(List.of(recording("mbid-1", "좋니", "윤종신")));

        final CatalogBrowseImportCommand command = new CatalogBrowseImportCommand(
                repository, client, properties(100));

        final BrowseSummary summary = command.runBrowseImport(List.of("윤종신"), 10, 50, false);

        assertThat(summary.skipped()).isEqualTo(1);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("runBrowseImport: 같은 실행 내 중복 mbId 는 두 번째부터 skip (in-memory dedup)")
    void run_duplicateMbIdWithinRun_skipsSecond() {
        final SongRepository repository = mock(SongRepository.class);
        final CatalogBrowseClient client = mock(CatalogBrowseClient.class);
        when(repository.findByMbId(any())).thenReturn(Optional.empty());
        when(repository.findByIsrc(any())).thenReturn(Optional.empty());
        when(repository.findByTitleAndArtist(any(), any())).thenReturn(Optional.empty());
        when(client.browseByArtist(eq("윤종신"), anyInt(), eq(0)))
                .thenReturn(List.of(recording("dup", "좋니", "윤종신"), recording("dup", "좋니", "윤종신")));

        final CatalogBrowseImportCommand command = new CatalogBrowseImportCommand(
                repository, client, properties(100));

        final BrowseSummary summary = command.runBrowseImport(List.of("윤종신"), 10, 50, false);

        assertThat(summary.inserted()).isEqualTo(1);
        assertThat(summary.skipped()).isEqualTo(1);
        verify(repository, times(1)).save(any());
    }

    @Test
    @DisplayName("runBrowseImport: 제목/아티스트 누락 recording 은 failed 카운트")
    void run_blankTitleOrArtist_counted() {
        final SongRepository repository = mock(SongRepository.class);
        final CatalogBrowseClient client = mock(CatalogBrowseClient.class);
        when(client.browseByArtist(eq("a"), anyInt(), eq(0)))
                .thenReturn(List.of(new BrowsedRecording("mbid", null, "a", null, null, null)));

        final CatalogBrowseImportCommand command = new CatalogBrowseImportCommand(
                repository, client, properties(100));

        final BrowseSummary summary = command.runBrowseImport(List.of("a"), 10, 50, false);

        assertThat(summary.failed()).isEqualTo(1);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("runBrowseImport: maxRecordingsPerArtist 상한까지만 적재")
    void run_maxPerArtist_capsInserts() {
        final SongRepository repository = mock(SongRepository.class);
        final CatalogBrowseClient client = mock(CatalogBrowseClient.class);
        when(repository.findByMbId(any())).thenReturn(Optional.empty());
        when(repository.findByIsrc(any())).thenReturn(Optional.empty());
        when(repository.findByTitleAndArtist(any(), any())).thenReturn(Optional.empty());
        when(client.browseByArtist(eq("a"), anyInt(), eq(0)))
                .thenReturn(List.of(
                        recording("m1", "t1", "a"),
                        recording("m2", "t2", "a"),
                        recording("m3", "t3", "a"),
                        recording("m4", "t4", "a")));

        final CatalogBrowseImportCommand command = new CatalogBrowseImportCommand(
                repository, client, properties(100));

        final BrowseSummary summary = command.runBrowseImport(List.of("a"), 10, 2, false);

        assertThat(summary.inserted()).isEqualTo(2);
        verify(repository, times(2)).save(any());
    }

    @Test
    @DisplayName("runBrowseImport: 페이지가 가득 차면 offset 을 늘려 다음 페이지를 가져온다")
    void run_pagesThroughOffsets() {
        final SongRepository repository = mock(SongRepository.class);
        final CatalogBrowseClient client = mock(CatalogBrowseClient.class);
        when(repository.findByMbId(any())).thenReturn(Optional.empty());
        when(repository.findByIsrc(any())).thenReturn(Optional.empty());
        when(repository.findByTitleAndArtist(any(), any())).thenReturn(Optional.empty());
        // pageSize=2: 첫 페이지 가득(2) → 다음 offset, 둘째 페이지 1건(부분) → 종료.
        when(client.browseByArtist(eq("a"), eq(2), eq(0)))
                .thenReturn(List.of(recording("m1", "t1", "a"), recording("m2", "t2", "a")));
        when(client.browseByArtist(eq("a"), eq(2), eq(2)))
                .thenReturn(List.of(recording("m3", "t3", "a")));

        final CatalogBrowseImportCommand command = new CatalogBrowseImportCommand(
                repository, client, properties(2));

        final BrowseSummary summary = command.runBrowseImport(List.of("a"), 10, 50, false);

        assertThat(summary.inserted()).isEqualTo(3);
        verify(client, times(1)).browseByArtist("a", 2, 0);
        verify(client, times(1)).browseByArtist("a", 2, 2);
    }

    @Test
    @DisplayName("runBrowseImport: 503 rate limit 시 batch 전체 중단(aborted=true)")
    void run_rateLimit_aborts() {
        final SongRepository repository = mock(SongRepository.class);
        final CatalogBrowseClient client = mock(CatalogBrowseClient.class);
        when(client.browseByArtist(eq("a"), anyInt(), anyInt()))
                .thenThrow(new CatalogBrowseRateLimitException("exhausted"));

        final CatalogBrowseImportCommand command = new CatalogBrowseImportCommand(
                repository, client, properties(100));

        final BrowseSummary summary = command.runBrowseImport(List.of("a", "b"), 10, 50, false);

        assertThat(summary.aborted()).isTrue();
        assertThat(summary.inserted()).isZero();
        // 첫 아티스트에서 중단 — 두 번째 아티스트는 호출하지 않는다.
        verify(client, never()).browseByArtist(eq("b"), anyInt(), anyInt());
    }

    @Test
    @DisplayName("runBrowseImport: dryRun 이면 적재 가능 후보만 집계하고 save 하지 않는다")
    void run_dryRun_doesNotSave() {
        final SongRepository repository = mock(SongRepository.class);
        final CatalogBrowseClient client = mock(CatalogBrowseClient.class);
        when(repository.findByMbId(any())).thenReturn(Optional.empty());
        when(repository.findByIsrc(any())).thenReturn(Optional.empty());
        when(repository.findByTitleAndArtist(any(), any())).thenReturn(Optional.empty());
        when(client.browseByArtist(eq("a"), anyInt(), eq(0)))
                .thenReturn(List.of(recording("m1", "t1", "a")));

        final CatalogBrowseImportCommand command = new CatalogBrowseImportCommand(
                repository, client, properties(100));

        final BrowseSummary summary = command.runBrowseImport(List.of("a"), 10, 50, true);

        assertThat(summary.inserted()).isEqualTo(1);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("runBrowseImport: maxArtists 상한까지만 아티스트를 처리한다")
    void run_maxArtists_capsArtists() {
        final SongRepository repository = mock(SongRepository.class);
        final CatalogBrowseClient client = mock(CatalogBrowseClient.class);
        when(client.browseByArtist(any(), anyInt(), anyInt())).thenReturn(List.of());

        final CatalogBrowseImportCommand command = new CatalogBrowseImportCommand(
                repository, client, properties(100));

        final BrowseSummary summary = command.runBrowseImport(List.of("a", "b", "c"), 2, 50, false);

        assertThat(summary.artistsProcessed()).isEqualTo(2);
        verify(client, never()).browseByArtist(eq("c"), anyInt(), anyInt());
    }
}
