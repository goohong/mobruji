package com.mobruji.song.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.song.domain.AudioAnalysisFailedException;
import com.mobruji.song.domain.AudioAnalysisResult;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * {@link SongAudioBackfillCommand} Micrometer 카운터 emit 검증.
 *
 * <p>spec: {@code docs/features/observability-baseline.md} §5-3 표 — `mobruji.song.audio.backfill.requested`
 * / `.success` / `.failed{reason}` 3 카운터의 의미 정합 가드. 표가 단일 진실이므로 metric 이름은 본 클래스 상수가
 * 아닌 운영 정의(observability-baseline.md) 와 1:1 일치해야 한다.
 *
 * <p>검증 항목:
 * <ol>
 * <li>runBackfill 진입 1회 = requested +1 (대상 0건 noop 포함)</li>
 * <li>곡 단위 성공 = success +1 (DB 적용/임계 미달 무관)</li>
 * <li>곡 단위 실패 = failed +1 (`reason` 라벨 = timeout/spawn_error/json_parse/song_apply/other)</li>
 * </ol>
 */
class SongAudioBackfillCommandMetricsTest {

    private static Song seed(final String title) {
        return Song.builder()
                .title(title)
                .artist("artist-" + title)
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .lowMidi(60)
                .highMidi(70)
                .build();
    }

    @Test
    @DisplayName("runBackfill 진입 시 mobruji.song.audio.backfill.requested 카운터가 +1 — 대상 0건 noop 포함")
    void backfillRequested_incrementedOnEntry() {
        // given
        final MeterRegistry registry = new SimpleMeterRegistry();
        final SongRepository repo = mock(SongRepository.class);
        when(repo.findAll()).thenReturn(List.of());
        final AudioAnalysisRunner runner = mock(AudioAnalysisRunner.class);
        final SongAudioBackfillCommand cmd = new SongAudioBackfillCommand(repo, runner, registry);

        // when
        cmd.runBackfill(0.6);
        cmd.runBackfill(0.6);

        // then
        final Counter requested = registry.find(SongAudioBackfillCommand.METRIC_BACKFILL_REQUESTED).counter();
        assertThat(requested).isNotNull();
        assertThat(requested.count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("곡 단위 성공 1건 = mobruji.song.audio.backfill.success +1 (DB 적용/임계 미달 무관)")
    void backfillSuccess_incrementedPerSong() {
        // given: 두 곡 분석 성공 (한 곡은 임계 통과, 한 곡은 미달)
        final MeterRegistry registry = new SimpleMeterRegistry();
        final Song high = seed("high");
        final Song low = seed("low");
        final SongRepository repo = mock(SongRepository.class);
        when(repo.findAll()).thenReturn(List.of(high, low));
        final AudioAnalysisRunner runner = mock(AudioAnalysisRunner.class);
        when(runner.analyzeByMetadata("high", "artist-high"))
                .thenReturn(new AudioAnalysisResult(57, 78, "C", 120.0, 200.0, 0.9, "v"));
        when(runner.analyzeByMetadata("low", "artist-low"))
                .thenReturn(new AudioAnalysisResult(55, 80, "C", 120.0, 200.0, 0.3, "v"));
        final SongAudioBackfillCommand cmd = new SongAudioBackfillCommand(repo, runner, registry);

        // when
        cmd.runBackfill(0.6);

        // then: success 는 분석 성공 곡 수 (임계 미달도 분석은 성공)
        final Counter success = registry.find(SongAudioBackfillCommand.METRIC_BACKFILL_SUCCESS).counter();
        assertThat(success).isNotNull();
        assertThat(success.count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("AudioAnalysisFailedException timeout 메시지 = mobruji.song.audio.backfill.failed{reason=timeout} +1")
    void backfillFailed_timeoutReason() {
        // given
        final MeterRegistry registry = new SimpleMeterRegistry();
        final Song s = seed("t");
        final SongRepository repo = mock(SongRepository.class);
        when(repo.findAll()).thenReturn(List.of(s));
        final AudioAnalysisRunner runner = mock(AudioAnalysisRunner.class);
        when(runner.analyzeByMetadata("t", "artist-t"))
                .thenThrow(new AudioAnalysisFailedException("audio analysis timeout after 60s (tag=meta:t)"));
        final SongAudioBackfillCommand cmd = new SongAudioBackfillCommand(repo, runner, registry);

        // when
        cmd.runBackfill(0.6);

        // then
        final Counter failed = registry.find(SongAudioBackfillCommand.METRIC_BACKFILL_FAILED)
                .tag("reason", SongAudioBackfillCommand.REASON_TIMEOUT).counter();
        assertThat(failed).isNotNull();
        assertThat(failed.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("AudioAnalysisFailedException 'failed to parse' 메시지 = failed{reason=json_parse} +1")
    void backfillFailed_parseReason() {
        // given
        final MeterRegistry registry = new SimpleMeterRegistry();
        final Song s = seed("p");
        final SongRepository repo = mock(SongRepository.class);
        when(repo.findAll()).thenReturn(List.of(s));
        final AudioAnalysisRunner runner = mock(AudioAnalysisRunner.class);
        when(runner.analyzeByMetadata("p", "artist-p"))
                .thenThrow(new AudioAnalysisFailedException("failed to parse audio analysis JSON"));
        final SongAudioBackfillCommand cmd = new SongAudioBackfillCommand(repo, runner, registry);

        // when
        cmd.runBackfill(0.6);

        // then
        final Counter failed = registry.find(SongAudioBackfillCommand.METRIC_BACKFILL_FAILED)
                .tag("reason", SongAudioBackfillCommand.REASON_JSON_PARSE).counter();
        assertThat(failed).isNotNull();
        assertThat(failed.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("AudioAnalysisFailedException 'Cannot run program' = failed{reason=spawn_error} +1")
    void backfillFailed_spawnErrorReason() {
        // given
        final MeterRegistry registry = new SimpleMeterRegistry();
        final Song s = seed("s");
        final SongRepository repo = mock(SongRepository.class);
        when(repo.findAll()).thenReturn(List.of(s));
        final AudioAnalysisRunner runner = mock(AudioAnalysisRunner.class);
        when(runner.analyzeByMetadata("s", "artist-s"))
                .thenThrow(new AudioAnalysisFailedException(
                        "failed to spawn audio analysis process: Cannot run program 'python3'"));
        final SongAudioBackfillCommand cmd = new SongAudioBackfillCommand(repo, runner, registry);

        // when
        cmd.runBackfill(0.6);

        // then
        final Counter failed = registry.find(SongAudioBackfillCommand.METRIC_BACKFILL_FAILED)
                .tag("reason", SongAudioBackfillCommand.REASON_SPAWN_ERROR).counter();
        assertThat(failed).isNotNull();
        assertThat(failed.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("RuntimeException (예: IO 계열) = failed{reason=other} +1")
    void backfillFailed_otherReason() {
        // given
        final MeterRegistry registry = new SimpleMeterRegistry();
        final Song s = seed("o");
        final SongRepository repo = mock(SongRepository.class);
        when(repo.findAll()).thenReturn(List.of(s));
        final AudioAnalysisRunner runner = mock(AudioAnalysisRunner.class);
        when(runner.analyzeByMetadata("o", "artist-o"))
                .thenThrow(new IllegalStateException("unexpected IO failure"));
        final SongAudioBackfillCommand cmd = new SongAudioBackfillCommand(repo, runner, registry);

        // when
        cmd.runBackfill(0.6);

        // then
        final Counter failed = registry.find(SongAudioBackfillCommand.METRIC_BACKFILL_FAILED)
                .tag("reason", SongAudioBackfillCommand.REASON_OTHER).counter();
        assertThat(failed).isNotNull();
        assertThat(failed.count()).isEqualTo(1.0);
    }
}
