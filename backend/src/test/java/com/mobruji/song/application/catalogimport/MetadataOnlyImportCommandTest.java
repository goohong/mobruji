package com.mobruji.song.application.catalogimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.annotation.Profile;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.mobruji.song.application.catalogimport.MetadataOnlyImportCommand.CandidateEntry;
import com.mobruji.song.application.catalogimport.MetadataOnlyImportCommand.ImportSummary;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

/**
 * {@link MetadataOnlyImportCommand} 단위 테스트 — throttle 0 으로 속도 보장. 멱등성/격리/옵션 파싱 회귀 가드.
 */
class MetadataOnlyImportCommandTest {

    private static final CatalogImportProperties PROPERTIES = new CatalogImportProperties(
            "https://musicbrainz.org/ws/2",
            "mobruji-backend/0.1 (+test)",
            Duration.ofSeconds(5),
            Duration.ZERO,
            0.3);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static CandidateEntry candidate(final String title) {
        return new CandidateEntry(title, "artist-" + title);
    }

    @Test
    @DisplayName("runImport: 신규 곡은 EXTERNAL_API + UNKNOWN key + 메타데이터로 insert")
    void runImport_newSong_insertsWithMetadata() {
        final SongRepository repository = mock(SongRepository.class);
        final SongMetadataLookupClient lookup = mock(SongMetadataLookupClient.class);
        when(repository.findByTitleAndArtist("좋니", "artist-좋니")).thenReturn(Optional.empty());
        when(lookup.lookupMetadata("좋니", "artist-좋니"))
                .thenReturn(Optional.of(new ImportedSongMetadata("mbid-1", "KRA401700001", 2017, "k-pop")));

        final MetadataOnlyImportCommand command = new MetadataOnlyImportCommand(repository, lookup, PROPERTIES,
                OBJECT_MAPPER);

        final ImportSummary summary = command.runImport(List.of(candidate("좋니")));

        assertThat(summary.total()).isEqualTo(1);
        assertThat(summary.inserted()).isEqualTo(1);
        assertThat(summary.insertedWithoutMetadata()).isZero();
        assertThat(summary.skipped()).isZero();
        assertThat(summary.failed()).isZero();

        final ArgumentCaptor<Song> saved = ArgumentCaptor.forClass(Song.class);
        verify(repository, times(1)).save(saved.capture());
        final Song song = saved.getValue();
        assertThat(song.getMetadataSource()).isEqualTo(MetadataSource.EXTERNAL_API);
        assertThat(song.getKeyOriginal()).isEqualTo(MusicalKey.UNKNOWN);
        assertThat(song.getReleaseYear()).isEqualTo(2017);
        assertThat(song.getGenre()).isEqualTo("k-pop");
        assertThat(song.getIsrc()).isEqualTo("KRA401700001");
        assertThat(song.getMetadataConfidence()).isEqualTo(0.3);
        // 음역대/bpm 은 채우지 않는다 — 자체 분석 후속.
        assertThat(song.getLowMidi()).isNull();
        assertThat(song.getHighMidi()).isNull();
        assertThat(song.getBpm()).isNull();
    }

    @Test
    @DisplayName("runImport: 동일 (title, artist) 이미 존재하면 skip — lookup 호출 없음")
    void runImport_existingTitleArtist_skips() {
        final SongRepository repository = mock(SongRepository.class);
        final SongMetadataLookupClient lookup = mock(SongMetadataLookupClient.class);
        when(repository.findByTitleAndArtist("좋니", "artist-좋니"))
                .thenReturn(Optional.of(mock(Song.class)));

        final MetadataOnlyImportCommand command = new MetadataOnlyImportCommand(repository, lookup, PROPERTIES,
                OBJECT_MAPPER);

        final ImportSummary summary = command.runImport(List.of(candidate("좋니")));

        assertThat(summary.skipped()).isEqualTo(1);
        assertThat(summary.inserted()).isZero();
        verify(lookup, never()).lookupMetadata(any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("runImport: 동일 ISRC 이미 존재하면 skip (제목/아티스트 표기 무관 멱등)")
    void runImport_existingIsrc_skips() {
        final SongRepository repository = mock(SongRepository.class);
        final SongMetadataLookupClient lookup = mock(SongMetadataLookupClient.class);
        when(repository.findByTitleAndArtist("좋니", "artist-좋니")).thenReturn(Optional.empty());
        when(lookup.lookupMetadata("좋니", "artist-좋니"))
                .thenReturn(Optional.of(new ImportedSongMetadata("mbid-1", "KRA401700001", 2017, "k-pop")));
        when(repository.findByIsrc("KRA401700001")).thenReturn(Optional.of(mock(Song.class)));

        final MetadataOnlyImportCommand command = new MetadataOnlyImportCommand(repository, lookup, PROPERTIES,
                OBJECT_MAPPER);

        final ImportSummary summary = command.runImport(List.of(candidate("좋니")));

        assertThat(summary.skipped()).isEqualTo(1);
        assertThat(summary.inserted()).isZero();
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("runImport: MusicBrainz 무매칭이어도 곡은 insert (메타 null) → 자체 분석 후보로 진입")
    void runImport_lookupEmpty_insertsWithoutMetadata() {
        final SongRepository repository = mock(SongRepository.class);
        final SongMetadataLookupClient lookup = mock(SongMetadataLookupClient.class);
        when(repository.findByTitleAndArtist("희귀곡", "artist-희귀곡")).thenReturn(Optional.empty());
        when(lookup.lookupMetadata("희귀곡", "artist-희귀곡")).thenReturn(Optional.empty());

        final MetadataOnlyImportCommand command = new MetadataOnlyImportCommand(repository, lookup, PROPERTIES,
                OBJECT_MAPPER);

        final ImportSummary summary = command.runImport(List.of(candidate("희귀곡")));

        assertThat(summary.insertedWithoutMetadata()).isEqualTo(1);
        assertThat(summary.inserted()).isZero();

        final ArgumentCaptor<Song> saved = ArgumentCaptor.forClass(Song.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getMetadataSource()).isEqualTo(MetadataSource.EXTERNAL_API);
        assertThat(saved.getValue().getIsrc()).isNull();
        assertThat(saved.getValue().getReleaseYear()).isNull();
    }

    @Test
    @DisplayName("runImport: lookup 이 RuntimeException 던져도 곡 단위 격리, 다음 곡 진행")
    void runImport_lookupThrows_isolatesFailure() {
        final SongRepository repository = mock(SongRepository.class);
        final SongMetadataLookupClient lookup = mock(SongMetadataLookupClient.class);
        when(repository.findByTitleAndArtist(any(), any())).thenReturn(Optional.empty());
        when(lookup.lookupMetadata("fail", "artist-fail")).thenThrow(new RuntimeException("network kaboom"));
        when(lookup.lookupMetadata("ok", "artist-ok"))
                .thenReturn(Optional.of(new ImportedSongMetadata("mbid", null, 2020, null)));

        final MetadataOnlyImportCommand command = new MetadataOnlyImportCommand(repository, lookup, PROPERTIES,
                OBJECT_MAPPER);

        final ImportSummary summary = command.runImport(List.of(candidate("fail"), candidate("ok")));

        assertThat(summary.total()).isEqualTo(2);
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.inserted()).isEqualTo(1);
        verify(repository, times(1)).save(any());
    }

    @Test
    @DisplayName("runImport: title/artist blank 후보는 failed 카운트, 다음 곡 진행")
    void runImport_blankCandidate_counted() {
        final SongRepository repository = mock(SongRepository.class);
        final SongMetadataLookupClient lookup = mock(SongMetadataLookupClient.class);

        final MetadataOnlyImportCommand command = new MetadataOnlyImportCommand(repository, lookup, PROPERTIES,
                OBJECT_MAPPER);

        final ImportSummary summary = command.runImport(List.of(new CandidateEntry("  ", "artist")));

        assertThat(summary.failed()).isEqualTo(1);
        verify(repository, never()).findByTitleAndArtist(any(), any());
        verify(lookup, never()).lookupMetadata(any(), any());
    }

    // ───────────────────── 옵션 파싱 / 프로파일 회귀 가드 ─────────────────────

    @Test
    @DisplayName("@Profile 는 !test 로 고정 (통합 테스트 영향 회귀 가드)")
    void classProfile_excludesTest() {
        final Profile profile = MetadataOnlyImportCommand.class.getAnnotation(Profile.class);
        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("!test");
    }

    @Test
    @DisplayName("OPTION_KEY 는 mobruji.import-catalog 로 고정 (운영 명령 정합 회귀 가드)")
    void optionKey_isStable() {
        assertThat(MetadataOnlyImportCommand.OPTION_KEY).isEqualTo("mobruji.import-catalog");
    }

    @Test
    @DisplayName("run: 옵션 부재 시 no-op (평시 부팅 회귀 가드)")
    void run_noOption_isNoOp() throws Exception {
        final SongRepository repository = mock(SongRepository.class);
        final SongMetadataLookupClient lookup = mock(SongMetadataLookupClient.class);
        final MetadataOnlyImportCommand command = new MetadataOnlyImportCommand(repository, lookup, PROPERTIES,
                OBJECT_MAPPER);

        command.run(new DefaultApplicationArguments());

        verify(repository, never()).findByTitleAndArtist(any(), any());
        verify(lookup, never()).lookupMetadata(any(), any());
    }

    @Test
    @DisplayName("run: --mobruji.import-catalog=false → no-op")
    void run_optionFalse_isNoOp() throws Exception {
        final SongRepository repository = mock(SongRepository.class);
        final SongMetadataLookupClient lookup = mock(SongMetadataLookupClient.class);
        final MetadataOnlyImportCommand command = new MetadataOnlyImportCommand(repository, lookup, PROPERTIES,
                OBJECT_MAPPER);

        command.run(new DefaultApplicationArguments("--mobruji.import-catalog=false"));

        verify(repository, never()).findByTitleAndArtist(any(), any());
    }

    @Test
    @DisplayName("run: null args 면 no-op (방어 회귀 가드)")
    void run_nullArgs_isNoOp() throws Exception {
        final SongRepository repository = mock(SongRepository.class);
        final SongMetadataLookupClient lookup = mock(SongMetadataLookupClient.class);
        final MetadataOnlyImportCommand command = new MetadataOnlyImportCommand(repository, lookup, PROPERTIES,
                OBJECT_MAPPER);

        command.run(null);

        verify(repository, never()).findByTitleAndArtist(any(), any());
    }

    @Test
    @DisplayName("loadCandidates: classpath 후보 JSON 을 (title, artist) 로 파싱한다")
    void loadCandidates_parsesClasspathJson() throws Exception {
        final SongRepository repository = mock(SongRepository.class);
        final SongMetadataLookupClient lookup = mock(SongMetadataLookupClient.class);
        final MetadataOnlyImportCommand command = new MetadataOnlyImportCommand(repository, lookup, PROPERTIES,
                OBJECT_MAPPER);

        final List<CandidateEntry> candidates = command.loadCandidates();

        assertThat(candidates).isNotEmpty();
        assertThat(candidates).allSatisfy(entry -> {
            assertThat(entry.title()).isNotBlank();
            assertThat(entry.artist()).isNotBlank();
        });
    }
}
