package com.mobruji.song.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.springframework.boot.DefaultApplicationArguments;

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
                .thenReturn(new AudioAnalysisResult(55, 80, "C", 120.0, 200.0, 0.40, "v"));
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
        assertThat(summary.skippedImplausibleRange()).isZero();
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
    @DisplayName("runBackfill: confidence 통과해도 비합리 음역대는 거부되고 skippedImplausibleRange 로 집계 (#1725)")
    void runBackfill_implausibleRange_rejectedAndCounted() {
        // given: confidence 는 충분히 높지만 음역이 가창 한계를 벗어난 곡 2종 + 정상 곡 1
        final Song bassMisdetect = seedSong("bass", 60, 70);
        final Song octaveFold = seedSong("octave", 60, 70);
        final Song ok = seedSong("ok", 60, 70);

        final SongRepository repo = mock(SongRepository.class);
        when(repo.findAll()).thenReturn(List.of(bassMisdetect, octaveFold, ok));

        final AudioAnalysisRunner runner = mock(AudioAnalysisRunner.class);
        // lowMidi=30 < C2(36) — 반주 저음 오검출. highMidi 는 정상.
        when(runner.analyzeByMetadata("bass", "artist-bass"))
                .thenReturn(new AudioAnalysisResult(30, 70, "C", 120.0, 200.0, 0.95, "v"));
        // span 42 > 40 — 옥타브 폴딩 의심.
        when(runner.analyzeByMetadata("octave", "artist-octave"))
                .thenReturn(new AudioAnalysisResult(40, 82, "C", 120.0, 200.0, 0.95, "v"));
        when(runner.analyzeByMetadata("ok", "artist-ok"))
                .thenReturn(new AudioAnalysisResult(57, 78, "C", 120.0, 200.0, 0.90, "v"));

        final SongAudioBackfillCommand cmd = new SongAudioBackfillCommand(repo, runner, new SimpleMeterRegistry());

        final SongAudioBackfillCommand.BackfillSummary summary = cmd.runBackfill(0.6);

        // then: 분석 성공 3, 비합리 2건 거부, 정상 1곡만 적용
        assertThat(summary.analyzed()).isEqualTo(3);
        assertThat(summary.successful()).isEqualTo(3);
        assertThat(summary.updated()).isEqualTo(1);
        assertThat(summary.skippedImplausibleRange()).isEqualTo(2);
        assertThat(summary.skippedLowConfidence()).isZero();
        assertThat(summary.failed()).isZero();
        // 비합리 거부 곡은 source/midi 보존, 정상 곡만 갱신
        assertThat(bassMisdetect.getMetadataSource()).isEqualTo(MetadataSource.MANUAL_SEED);
        assertThat(bassMisdetect.getLowMidi()).isEqualTo(60);
        assertThat(octaveFold.getMetadataSource()).isEqualTo(MetadataSource.MANUAL_SEED);
        assertThat(ok.getMetadataSource()).isEqualTo(MetadataSource.AUDIO_ANALYSIS);
        verify(repo, times(1)).save(any(Song.class));
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
        assertThat(summary.skippedImplausibleRange()).isZero();
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
        assertThat(summary.skippedImplausibleRange()).isZero();
        assertThat(summary.failed()).isZero();
        verify(runner, never()).analyzeByMetadata(anyString(), anyString());
        verify(repo, never()).save(ArgumentMatchers.any());
    }

    @Test
    @DisplayName("runBoundedCandidateBackfill: 후보 중 limit 만큼만 분석, 전체(findAll) 미사용")
    void runBoundedCandidateBackfill_limitsCandidates() {
        // given: backfill 후보 3곡, limit 2
        final Song c1 = seedSong("c1", null, null);
        final Song c2 = seedSong("c2", null, null);
        final Song c3 = seedSong("c3", null, null);

        final SongRepository repo = mock(SongRepository.class);
        when(repo.findCandidatesForBackfill(0.6)).thenReturn(List.of(c1, c2, c3));

        final AudioAnalysisRunner runner = mock(AudioAnalysisRunner.class);
        when(runner.analyzeByMetadata("c1", "artist-c1"))
                .thenReturn(new AudioAnalysisResult(57, 78, "C", 120.0, 200.0, 0.85, "v"));
        when(runner.analyzeByMetadata("c2", "artist-c2"))
                .thenReturn(new AudioAnalysisResult(55, 80, "C", 120.0, 200.0, 0.80, "v"));

        final SongAudioBackfillCommand cmd = new SongAudioBackfillCommand(repo, runner, new SimpleMeterRegistry());

        // when
        final SongAudioBackfillCommand.BackfillSummary summary = cmd.runBoundedCandidateBackfill(2, 0.6);

        // then: 앞 2곡만 분석/적용, 3번째 곡은 손대지 않음
        assertThat(summary.analyzed()).isEqualTo(2);
        assertThat(summary.successful()).isEqualTo(2);
        assertThat(summary.updated()).isEqualTo(2);
        verify(runner, times(2)).analyzeByMetadata(anyString(), anyString());
        verify(runner, never()).analyzeByMetadata("c3", "artist-c3");
        verify(repo, never()).findAll();
        assertThat(c3.getMetadataSource()).isEqualTo(MetadataSource.MANUAL_SEED);
    }

    @Test
    @DisplayName("runBoundedCandidateBackfill: limit 이 후보 수보다 크면 전체 후보 처리")
    void runBoundedCandidateBackfill_limitExceedsCandidates_processesAll() {
        final Song c1 = seedSong("c1", null, null);
        final SongRepository repo = mock(SongRepository.class);
        when(repo.findCandidatesForBackfill(0.6)).thenReturn(List.of(c1));

        final AudioAnalysisRunner runner = mock(AudioAnalysisRunner.class);
        when(runner.analyzeByMetadata("c1", "artist-c1"))
                .thenReturn(new AudioAnalysisResult(57, 78, "C", 120.0, 200.0, 0.85, "v"));

        final SongAudioBackfillCommand cmd = new SongAudioBackfillCommand(repo, runner, new SimpleMeterRegistry());

        final SongAudioBackfillCommand.BackfillSummary summary = cmd.runBoundedCandidateBackfill(10, 0.6);

        assertThat(summary.analyzed()).isEqualTo(1);
    }

    @Test
    @DisplayName("run: --backfill-audio.limit 지정 시 후보 selective query 경로로 분기 (findAll 미사용)")
    void run_withLimitOption_routesToBoundedCandidates() {
        final Song c1 = seedSong("c1", null, null);
        final SongRepository repo = mock(SongRepository.class);
        when(repo.findCandidatesForBackfill(SongAudioBackfillCommand.DEFAULT_CONFIDENCE_THRESHOLD))
                .thenReturn(List.of(c1));

        final AudioAnalysisRunner runner = mock(AudioAnalysisRunner.class);
        when(runner.analyzeByMetadata("c1", "artist-c1"))
                .thenReturn(new AudioAnalysisResult(57, 78, "C", 120.0, 200.0, 0.85, "v"));

        final SongAudioBackfillCommand cmd = new SongAudioBackfillCommand(repo, runner, new SimpleMeterRegistry());

        cmd.run(new DefaultApplicationArguments(
                "--mobruji.backfill-audio=true", "--mobruji.backfill-audio.limit=5"));

        verify(repo, times(1)).findCandidatesForBackfill(
                SongAudioBackfillCommand.DEFAULT_CONFIDENCE_THRESHOLD);
        verify(repo, never()).findAll();
    }

    @Test
    @DisplayName("run: limit 옵션 없으면 기존 전체(findAll) 경로 유지")
    void run_withoutLimitOption_usesFindAll() {
        final SongRepository repo = mock(SongRepository.class);
        when(repo.findAll()).thenReturn(List.of());
        final AudioAnalysisRunner runner = mock(AudioAnalysisRunner.class);

        final SongAudioBackfillCommand cmd = new SongAudioBackfillCommand(repo, runner, new SimpleMeterRegistry());

        cmd.run(new DefaultApplicationArguments("--mobruji.backfill-audio=true"));

        verify(repo, times(1)).findAll();
        verify(repo, never()).findCandidatesForBackfill(ArgumentMatchers.anyDouble());
    }

    @Test
    @DisplayName("run: limit 값이 0/음수/비정수면 fail-fast (IllegalArgumentException)")
    void run_invalidLimit_throws() {
        final SongRepository repo = mock(SongRepository.class);
        final AudioAnalysisRunner runner = mock(AudioAnalysisRunner.class);
        final SongAudioBackfillCommand cmd = new SongAudioBackfillCommand(repo, runner, new SimpleMeterRegistry());

        assertThatThrownBy(() -> cmd.run(new DefaultApplicationArguments(
                "--mobruji.backfill-audio=true", "--mobruji.backfill-audio.limit=0")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cmd.run(new DefaultApplicationArguments(
                "--mobruji.backfill-audio=true", "--mobruji.backfill-audio.limit=abc")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(runner, never()).analyzeByMetadata(anyString(), anyString());
    }
}
