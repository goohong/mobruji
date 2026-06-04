package com.mobruji.song.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.OptionalInt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

/**
 * {@link SongVocalRangeEstimateCommand} 단위 테스트 — 음역대 미보유 곡에 메타 추정값을 채우는 batch 의 적용/skip/limit
 * 분기를 {@link SongRepository} mock 으로 검증한다 (#1778).
 */
class SongVocalRangeEstimateCommandTest {

    private static Song missingSong(final String title, final MusicalKey key, final String genre) {
        return Song.builder()
                .title(title)
                .artist("artist-" + title)
                .keyOriginal(key)
                .genre(genre)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
    }

    @Test
    @DisplayName("runEstimate: 추정 가능한 곡은 채우고 ESTIMATED 로, 키 UNKNOWN 곡은 skip 집계")
    void runEstimate_mixedKeys_appliesAndSkips() {
        final Song ok1 = missingSong("ok1", MusicalKey.C_MAJOR, "발라드");
        final Song unknown = missingSong("unknown", MusicalKey.UNKNOWN, "댄스");
        final Song ok2 = missingSong("ok2", MusicalKey.G_MAJOR, null);

        final SongRepository repo = mock(SongRepository.class);
        when(repo.findMissingVocalRange()).thenReturn(List.of(ok1, unknown, ok2));

        final SongVocalRangeEstimateCommand cmd = new SongVocalRangeEstimateCommand(repo);

        final SongVocalRangeEstimateCommand.EstimateSummary summary = cmd.runEstimate(OptionalInt.empty());

        assertThat(summary.scanned()).isEqualTo(3);
        assertThat(summary.applied()).isEqualTo(2);
        assertThat(summary.skippedUnestimable()).isEqualTo(1);
        assertThat(summary.skippedNotApplied()).isZero();

        assertThat(ok1.getMetadataSource()).isEqualTo(MetadataSource.ESTIMATED);
        assertThat(ok1.getLowMidi()).isNotNull();
        assertThat(unknown.getMetadataSource()).isEqualTo(MetadataSource.MANUAL_SEED);
        assertThat(unknown.getLowMidi()).isNull();
        verify(repo, times(2)).save(any(Song.class));
    }

    @Test
    @DisplayName("runEstimate: limit 만큼만 chunk 처리 (id 순 앞 N곡)")
    void runEstimate_withLimit_chunksFirstN() {
        final Song m1 = missingSong("m1", MusicalKey.C_MAJOR, null);
        final Song m2 = missingSong("m2", MusicalKey.D_MAJOR, null);
        final Song m3 = missingSong("m3", MusicalKey.E_MAJOR, null);

        final SongRepository repo = mock(SongRepository.class);
        when(repo.findMissingVocalRange()).thenReturn(List.of(m1, m2, m3));

        final SongVocalRangeEstimateCommand cmd = new SongVocalRangeEstimateCommand(repo);

        final SongVocalRangeEstimateCommand.EstimateSummary summary = cmd.runEstimate(OptionalInt.of(2));

        assertThat(summary.scanned()).isEqualTo(2);
        assertThat(summary.applied()).isEqualTo(2);
        assertThat(m3.getMetadataSource()).isEqualTo(MetadataSource.MANUAL_SEED);
        assertThat(m3.getLowMidi()).isNull();
        verify(repo, times(2)).save(any(Song.class));
    }

    @Test
    @DisplayName("runEstimate: 미보유 곡 없으면 모든 카운트 0, save 호출 없음")
    void runEstimate_empty_noop() {
        final SongRepository repo = mock(SongRepository.class);
        when(repo.findMissingVocalRange()).thenReturn(List.of());

        final SongVocalRangeEstimateCommand cmd = new SongVocalRangeEstimateCommand(repo);

        final SongVocalRangeEstimateCommand.EstimateSummary summary = cmd.runEstimate(OptionalInt.empty());

        assertThat(summary.scanned()).isZero();
        assertThat(summary.applied()).isZero();
        verify(repo, never()).save(any(Song.class));
    }

    @Test
    @DisplayName("run: 옵션 없으면 no-op (findMissingVocalRange 미호출)")
    void run_withoutOption_noop() {
        final SongRepository repo = mock(SongRepository.class);
        final SongVocalRangeEstimateCommand cmd = new SongVocalRangeEstimateCommand(repo);

        cmd.run(new DefaultApplicationArguments());

        verify(repo, never()).findMissingVocalRange();
    }

    @Test
    @DisplayName("run: --estimate-range=true 면 미보유 곡 query 경로로 분기")
    void run_withOption_routesToMissingVocalRange() {
        final SongRepository repo = mock(SongRepository.class);
        when(repo.findMissingVocalRange()).thenReturn(List.of());

        final SongVocalRangeEstimateCommand cmd = new SongVocalRangeEstimateCommand(repo);

        cmd.run(new DefaultApplicationArguments("--mobruji.estimate-range=true"));

        verify(repo, times(1)).findMissingVocalRange();
    }

    @Test
    @DisplayName("run: limit 값이 0/음수/비정수면 fail-fast (IllegalArgumentException)")
    void run_invalidLimit_throws() {
        final SongRepository repo = mock(SongRepository.class);
        final SongVocalRangeEstimateCommand cmd = new SongVocalRangeEstimateCommand(repo);

        assertThatThrownBy(() -> cmd.run(new DefaultApplicationArguments(
                "--mobruji.estimate-range=true", "--mobruji.estimate-range.limit=0")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cmd.run(new DefaultApplicationArguments(
                "--mobruji.estimate-range=true", "--mobruji.estimate-range.limit=abc")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repo, never()).findMissingVocalRange();
    }
}
