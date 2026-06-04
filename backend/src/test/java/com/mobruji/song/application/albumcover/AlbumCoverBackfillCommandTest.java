package com.mobruji.song.application.albumcover;

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
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.annotation.Profile;

import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

/**
 * {@link AlbumCoverBackfillCommand} 단위 테스트. throttle 은 0 으로 설정해 테스트 속도 보장.
 */
class AlbumCoverBackfillCommandTest {

    private static final AlbumCoverProperties PROPERTIES = new AlbumCoverProperties(
            new AlbumCoverProperties.Itunes(
                    "https://itunes.apple.com/search",
                    "KR",
                    Duration.ofSeconds(5),
                    Duration.ZERO,
                    "600x600"),
            new AlbumCoverProperties.CoverArtArchive(
                    "https://musicbrainz.org/ws/2",
                    "https://coverartarchive.org",
                    "mobruji-backend/0.1 (+test)",
                    Duration.ofSeconds(5)));

    private static Song seed(final String title) {
        return Song.builder()
                .title(title).artist("artist-" + title)
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
    }

    @Test
    @DisplayName("runBackfill: 매칭 성공 곡은 save 호출, 실패 곡은 missed 카운트")
    void runBackfill_matched_savesAndCountsCorrectly() {
        final Song matched = seed("matched");
        final Song missed = seed("missed");
        final SongRepository repository = mock(SongRepository.class);
        final AlbumCoverLookupClient lookup = mock(AlbumCoverLookupClient.class);
        when(lookup.lookupAlbumCoverUrl("matched", "artist-matched"))
                .thenReturn(Optional.of("https://cdn.example.com/600x600bb.jpg"));
        when(lookup.lookupAlbumCoverUrl("missed", "artist-missed"))
                .thenReturn(Optional.empty());

        final AlbumCoverBackfillCommand command = new AlbumCoverBackfillCommand(repository, lookup, PROPERTIES);

        final AlbumCoverBackfillCommand.BackfillSummary summary = command.runBackfill(List.of(matched, missed));

        assertThat(summary.analyzed()).isEqualTo(2);
        assertThat(summary.matched()).isEqualTo(1);
        assertThat(summary.updated()).isEqualTo(1);
        assertThat(summary.missed()).isEqualTo(1);
        assertThat(matched.getAlbumCoverUrl()).isEqualTo("https://cdn.example.com/600x600bb.jpg");
        assertThat(missed.getAlbumCoverUrl()).isNull();
        verify(repository, times(1)).save(matched);
        verify(repository, never()).save(missed);
    }

    @Test
    @DisplayName("runBackfill: 이미 albumCoverUrl 이 있는 곡은 matched 여도 updated 카운트 증가 없음")
    void runBackfill_alreadyPresent_skipsSave() {
        final Song existing = Song.builder()
                .title("t").artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .albumCoverUrl("https://curator.example.com/manual.jpg")
                .build();
        final SongRepository repository = mock(SongRepository.class);
        final AlbumCoverLookupClient lookup = mock(AlbumCoverLookupClient.class);
        when(lookup.lookupAlbumCoverUrl("t", "a"))
                .thenReturn(Optional.of("https://cdn.example.com/new.jpg"));

        final AlbumCoverBackfillCommand command = new AlbumCoverBackfillCommand(repository, lookup, PROPERTIES);

        final AlbumCoverBackfillCommand.BackfillSummary summary = command.runBackfill(List.of(existing));

        assertThat(summary.matched()).isEqualTo(1);
        assertThat(summary.updated()).isZero();
        assertThat(existing.getAlbumCoverUrl()).isEqualTo("https://curator.example.com/manual.jpg");
        verify(repository, never()).save(any(Song.class));
    }

    @Test
    @DisplayName("runBackfill: lookup 이 RuntimeException 던져도 곡 단위 격리, 다음 곡 진행")
    void runBackfill_lookupThrows_isolatesFailure() {
        final Song failing = seed("fail");
        final Song ok = seed("ok");
        final SongRepository repository = mock(SongRepository.class);
        final AlbumCoverLookupClient lookup = mock(AlbumCoverLookupClient.class);
        when(lookup.lookupAlbumCoverUrl("fail", "artist-fail"))
                .thenThrow(new RuntimeException("network kaboom"));
        when(lookup.lookupAlbumCoverUrl("ok", "artist-ok"))
                .thenReturn(Optional.of("https://cdn.example.com/ok.jpg"));

        final AlbumCoverBackfillCommand command = new AlbumCoverBackfillCommand(repository, lookup, PROPERTIES);

        final AlbumCoverBackfillCommand.BackfillSummary summary = command.runBackfill(List.of(failing, ok));

        assertThat(summary.analyzed()).isEqualTo(2);
        assertThat(summary.matched()).isEqualTo(1);
        assertThat(summary.updated()).isEqualTo(1);
        assertThat(summary.missed()).isEqualTo(1);
        verify(repository, times(1)).save(ok);
    }

    @Test
    @DisplayName("runBackfill(): selective query (findMissingAlbumCover) 결과만 처리한다")
    void runBackfill_usesSelectiveQuery() {
        final Song missing = seed("missing");
        final SongRepository repository = mock(SongRepository.class);
        when(repository.findMissingAlbumCover()).thenReturn(List.of(missing));
        final AlbumCoverLookupClient lookup = mock(AlbumCoverLookupClient.class);
        when(lookup.lookupAlbumCoverUrl("missing", "artist-missing"))
                .thenReturn(Optional.of("https://cdn.example.com/m.jpg"));

        final AlbumCoverBackfillCommand command = new AlbumCoverBackfillCommand(repository, lookup, PROPERTIES);
        final AlbumCoverBackfillCommand.BackfillSummary summary = command.runBackfill();

        assertThat(summary.analyzed()).isEqualTo(1);
        verify(repository).findMissingAlbumCover();
    }

    // ───────────────────── 메타데이터 / 운영 명령 회귀 가드 ─────────────────────
    // @Profile/OPTION_KEY/run() 옵션 파싱 분기가 실수로 바뀌면 운영 명령이 깨지거나 통합 테스트 환경에서 외부 API 가
    // 호출될 수 있다.

    @Test
    @DisplayName("@Profile 이 없어야 한다 — on-demand admin 트리거(#1766)가 runBackfill 을 모든 프로파일에서 재사용 (회귀 가드)")
    void classProfile_isAbsent() {
        final Profile profile = AlbumCoverBackfillCommand.class.getAnnotation(Profile.class);
        // @Profile("!test") 로 되돌리면 on-demand 트리거가 test 프로파일에서 빈을 못 찾아 슬라이스/E2E 가 깨진다.
        assertThat(profile).as("on-demand 재사용을 위해 @Profile 은 제거되어야 한다").isNull();
    }

    @Test
    @DisplayName("OPTION_KEY 는 mobruji.backfill-album-cover 로 고정 (운영 명령 정합 회귀 가드)")
    void optionKey_isStable() {
        assertThat(AlbumCoverBackfillCommand.OPTION_KEY).isEqualTo("mobruji.backfill-album-cover");
    }

    @Test
    @DisplayName("run: 옵션 부재 시 no-op (평시 부팅 회귀 가드)")
    void run_noOption_isNoOp() {
        final SongRepository repository = mock(SongRepository.class);
        final AlbumCoverLookupClient lookup = mock(AlbumCoverLookupClient.class);
        final AlbumCoverBackfillCommand command = new AlbumCoverBackfillCommand(repository, lookup, PROPERTIES);

        command.run(new DefaultApplicationArguments());

        verify(repository, never()).findMissingAlbumCover();
        verify(lookup, never()).lookupAlbumCoverUrl(any(), any());
    }

    @Test
    @DisplayName("run: --mobruji.backfill-album-cover (값 없음) → backfill 트리거")
    void run_optionWithoutValue_triggersBackfill() {
        final SongRepository repository = mock(SongRepository.class);
        when(repository.findMissingAlbumCover()).thenReturn(List.of());
        final AlbumCoverLookupClient lookup = mock(AlbumCoverLookupClient.class);
        final AlbumCoverBackfillCommand command = new AlbumCoverBackfillCommand(repository, lookup, PROPERTIES);

        command.run(new DefaultApplicationArguments("--mobruji.backfill-album-cover"));

        verify(repository).findMissingAlbumCover();
    }

    @Test
    @DisplayName("run: --mobruji.backfill-album-cover=true → backfill 트리거")
    void run_optionTrue_triggersBackfill() {
        final SongRepository repository = mock(SongRepository.class);
        when(repository.findMissingAlbumCover()).thenReturn(List.of());
        final AlbumCoverLookupClient lookup = mock(AlbumCoverLookupClient.class);
        final AlbumCoverBackfillCommand command = new AlbumCoverBackfillCommand(repository, lookup, PROPERTIES);

        command.run(new DefaultApplicationArguments("--mobruji.backfill-album-cover=true"));

        verify(repository).findMissingAlbumCover();
    }

    @Test
    @DisplayName("run: --mobruji.backfill-album-cover=TRUE → backfill 트리거 (대소문자 무시 회귀 가드)")
    void run_optionTrueCaseInsensitive_triggersBackfill() {
        final SongRepository repository = mock(SongRepository.class);
        when(repository.findMissingAlbumCover()).thenReturn(List.of());
        final AlbumCoverLookupClient lookup = mock(AlbumCoverLookupClient.class);
        final AlbumCoverBackfillCommand command = new AlbumCoverBackfillCommand(repository, lookup, PROPERTIES);

        command.run(new DefaultApplicationArguments("--mobruji.backfill-album-cover=TRUE"));

        verify(repository).findMissingAlbumCover();
    }

    @Test
    @DisplayName("run: --mobruji.backfill-album-cover=false → no-op (옵션 끄기 회귀 가드)")
    void run_optionFalse_isNoOp() {
        final SongRepository repository = mock(SongRepository.class);
        final AlbumCoverLookupClient lookup = mock(AlbumCoverLookupClient.class);
        final AlbumCoverBackfillCommand command = new AlbumCoverBackfillCommand(repository, lookup, PROPERTIES);

        command.run(new DefaultApplicationArguments("--mobruji.backfill-album-cover=false"));

        verify(repository, never()).findMissingAlbumCover();
        verify(lookup, never()).lookupAlbumCoverUrl(any(), any());
    }

    @Test
    @DisplayName("run: null args 면 no-op (방어 회귀 가드)")
    void run_nullArgs_isNoOp() {
        final SongRepository repository = mock(SongRepository.class);
        final AlbumCoverLookupClient lookup = mock(AlbumCoverLookupClient.class);
        final AlbumCoverBackfillCommand command = new AlbumCoverBackfillCommand(repository, lookup, PROPERTIES);

        command.run(null);

        verify(repository, never()).findMissingAlbumCover();
    }
}
