package com.mobruji.song.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import com.mobruji.song.domain.AudioAnalysisFailedException;
import com.mobruji.song.domain.AudioAnalysisResult;
import com.mobruji.song.domain.Difficulty;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * {@link SongAudioBackfillCommand} 단위 테스트. {@link AudioAnalysisRunner} 와 {@link SongRepository} 를
 * Mockito 로 주입해 confidence 임계 / 실패 격리 / 요약 카운트를 검증한다.
 *
 * <p>실제 Python 호출은 {@link AudioAnalysisRunnerTest} 의 fake process 로 검증돼 있어 본 클래스에서는 다루지 않는다.
 */
class SongAudioBackfillCommandTest {

    private static Song seedSong(final String title, final Integer lowMidi, final Integer highMidi) {
        return Song.builder()
                .title(title)
                .artist("artist-" + title)
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .lowMidi(lowMidi)
                .highMidi(highMidi)
                .build();
    }

    @Test
    @DisplayName("runBackfill: 곡별로 analyze → 임계 통과는 update, 임계 미달은 skip 으로 집계")
    void runBackfill_mixedConfidence_countsCorrectly() {
        // given: 곡 3개 — high/low/high confidence
        final Song s1 = seedSong("high1", 60, 70);
        final Song s2 = seedSong("low", 60, 70);
        final Song s3 = seedSong("high2", 60, 70);

        final SongRepository repo = mock(SongRepository.class);
        when(repo.findAll()).thenReturn(List.of(s1, s2, s3));

        final AudioAnalysisRunner runner = mock(AudioAnalysisRunner.class);
        when(runner.analyzeByMetadata("high1", "artist-high1"))
                .thenReturn(new AudioAnalysisResult(57, 78, "C", 120.0, 200.0, 0.85, "v"));
        when(runner.analyzeByMetadata("low", "artist-low"))
                .thenReturn(new AudioAnalysisResult(50, 90, "C", 120.0, 200.0, 0.40, "v"));
        when(runner.analyzeByMetadata("high2", "artist-high2"))
                .thenReturn(new AudioAnalysisResult(55, 80, "C", 120.0, 200.0, 0.75, "v"));

        final SongAudioBackfillCommand cmd = new SongAudioBackfillCommand(repo, runner, new SimpleMeterRegistry());

        // when
        final SongAudioBackfillCommand.BackfillSummary summary = cmd.runBackfill(0.6);

        // then: analyzed=3, successful=3, updated=2, skipped=1, failed=0
        assertThat(summary.analyzed()).isEqualTo(3);
        assertThat(summary.successful()).isEqualTo(3);
        assertThat(summary.updated()).isEqualTo(2);
        assertThat(summary.skippedLowConfidence()).isEqualTo(1);
        assertThat(summary.failed()).isZero();

        // updated 된 두 곡만 save 호출
        verify(repo, times(2)).save(any(Song.class));

        // 적용된 곡은 source=AUDIO_ANALYSIS, 미적용 곡은 MANUAL_SEED 유지
        assertThat(s1.getMetadataSource()).isEqualTo(MetadataSource.AUDIO_ANALYSIS);
        assertThat(s1.getDifficulty()).isEqualTo(Difficulty.HARD);
        assertThat(s2.getMetadataSource()).isEqualTo(MetadataSource.MANUAL_SEED);
        assertThat(s3.getMetadataSource()).isEqualTo(MetadataSource.AUDIO_ANALYSIS);
    }

    @Test
    @DisplayName("runBackfill: 곡 1개의 분석 실패는 격리되고 나머지는 계속 처리")
    void runBackfill_singleFailure_isolatedAndCounted() {
        final Song s1 = seedSong("ok", 60, 70);
        final Song s2 = seedSong("fail", 60, 70);
        final Song s3 = seedSong("ok2", 60, 70);

        final SongRepository repo = mock(SongRepository.class);
        when(repo.findAll()).thenReturn(List.of(s1, s2, s3));

        final AudioAnalysisRunner runner = mock(AudioAnalysisRunner.class);
        when(runner.analyzeByMetadata("ok", "artist-ok"))
                .thenReturn(new AudioAnalysisResult(57, 78, "C", 120.0, 200.0, 0.90, "v"));
        when(runner.analyzeByMetadata("fail", "artist-fail"))
                .thenThrow(new AudioAnalysisFailedException("yt-dlp 403 (test)"));
        when(runner.analyzeByMetadata("ok2", "artist-ok2"))
                .thenReturn(new AudioAnalysisResult(55, 80, "C", 120.0, 200.0, 0.85, "v"));

        final SongAudioBackfillCommand cmd = new SongAudioBackfillCommand(repo, runner, new SimpleMeterRegistry());

        final SongAudioBackfillCommand.BackfillSummary summary = cmd.runBackfill(0.6);

        assertThat(summary.analyzed()).isEqualTo(3);
        assertThat(summary.successful()).isEqualTo(2);
        assertThat(summary.updated()).isEqualTo(2);
        assertThat(summary.skippedLowConfidence()).isZero();
        assertThat(summary.failed()).isEqualTo(1);
        // 실패한 곡은 source/midi 그대로
        assertThat(s2.getMetadataSource()).isEqualTo(MetadataSource.MANUAL_SEED);
        assertThat(s2.getLowMidi()).isEqualTo(60);
        verify(repo, times(2)).save(any(Song.class));
        verify(runner, times(3)).analyzeByMetadata(anyString(), anyString());
    }

    @Test
    @DisplayName("runBackfill: 빈 DB면 모든 카운트 0, save/analyze 호출 없음")
    void runBackfill_emptyDb_noop() {
        final SongRepository repo = mock(SongRepository.class);
        when(repo.findAll()).thenReturn(List.of());
        final AudioAnalysisRunner runner = mock(AudioAnalysisRunner.class);

        final SongAudioBackfillCommand cmd = new SongAudioBackfillCommand(repo, runner, new SimpleMeterRegistry());

        final SongAudioBackfillCommand.BackfillSummary summary = cmd.runBackfill(0.6);

        assertThat(summary.analyzed()).isZero();
        assertThat(summary.successful()).isZero();
        assertThat(summary.updated()).isZero();
        assertThat(summary.skippedLowConfidence()).isZero();
        assertThat(summary.failed()).isZero();
        verify(runner, never()).analyzeByMetadata(anyString(), anyString());
        verify(repo, never()).save(ArgumentMatchers.any());
    }
}
