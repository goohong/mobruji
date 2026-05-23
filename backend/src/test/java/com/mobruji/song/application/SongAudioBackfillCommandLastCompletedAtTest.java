package com.mobruji.song.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.song.domain.AudioAnalysisFailedException;
import com.mobruji.song.domain.AudioAnalysisResult;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * {@link SongAudioBackfillCommand#getLastBackfillCompletedAt()} 정적 상태 회귀 가드.
 *
 * <p>{@code LAST_BACKFILL_COMPLETED_AT} 는 static {@link AtomicReference} — admin 통계 API 가
 * {@link SongStatsService} 를 거쳐 마지막 batch 시각을 노출한다. JVM 라이프사이클 공유 상태이므로
 * 각 테스트는 reflection 으로 reset 해 격리한다.
 *
 * <p>가드 항목:
 * <ul>
 * <li>초기값 null (재기동 직후 첫 조회 가정)</li>
 * <li>runBackfill 후 non-null 갱신</li>
 * <li>빈 DB(noop) 호출도 시각 갱신 — "batch 가 돌긴 했다" 신호 의미 보존</li>
 * <li>전곡 실패도 시각 갱신 — 호출이 완료된 사실 자체가 기록 대상</li>
 * <li>순차 호출 시 monotonic 비감소</li>
 * <li>인스턴스 별개여도 동일 정적 값 관측</li>
 * <li>호출 직전 wall-clock 과 호출 후 시각의 시간적 일관성</li>
 * </ul>
 */
class SongAudioBackfillCommandLastCompletedAtTest {

    private static final String STATIC_FIELD_NAME = "LAST_BACKFILL_COMPLETED_AT";

    private static Song seedSong(final String title) {
        return Song.builder()
                .title(title)
                .artist("artist-" + title)
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .lowMidi(60)
                .highMidi(70)
                .build();
    }

    @SuppressWarnings("unchecked")
    private static AtomicReference<Instant> staticRef() throws Exception {
        final Field field = SongAudioBackfillCommand.class.getDeclaredField(STATIC_FIELD_NAME);
        field.setAccessible(true);
        return (AtomicReference<Instant>) field.get(null);
    }

    @BeforeEach
    void resetStaticState() throws Exception {
        staticRef().set(null);
    }

    @Test
    @DisplayName("초기 상태: getLastBackfillCompletedAt 은 null (재기동 직후 첫 조회 가정)")
    void initialState_isNull() {
        assertThat(SongAudioBackfillCommand.getLastBackfillCompletedAt()).isNull();
    }

    @Test
    @DisplayName("runBackfill 1회 완료 후: 마지막 시각이 non-null 로 갱신")
    void afterRunBackfill_isNonNull() {
        final Song song = seedSong("ok");
        final SongRepository repo = mock(SongRepository.class);
        when(repo.findAll()).thenReturn(List.of(song));
        final AudioAnalysisRunner runner = mock(AudioAnalysisRunner.class);
        when(runner.analyzeByMetadata("ok", "artist-ok"))
                .thenReturn(new AudioAnalysisResult(57, 78, "C", 120.0, 200.0, 0.85, "v"));
        final SongAudioBackfillCommand cmd = new SongAudioBackfillCommand(repo, runner, new SimpleMeterRegistry());

        cmd.runBackfill(0.6);

        assertThat(SongAudioBackfillCommand.getLastBackfillCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("빈 DB(noop) 호출도 시각을 갱신 — batch 가 돌았다는 사실 자체를 기록")
    void emptyDb_stillUpdatesTimestamp() {
        final SongRepository repo = mock(SongRepository.class);
        when(repo.findAll()).thenReturn(List.of());
        final AudioAnalysisRunner runner = mock(AudioAnalysisRunner.class);
        final SongAudioBackfillCommand cmd = new SongAudioBackfillCommand(repo, runner, new SimpleMeterRegistry());

        assertThat(SongAudioBackfillCommand.getLastBackfillCompletedAt()).isNull();
        cmd.runBackfill(0.6);
        assertThat(SongAudioBackfillCommand.getLastBackfillCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("전곡 실패해도 시각 갱신 — 호출 완료 자체가 기록 대상")
    void allFailures_stillUpdatesTimestamp() {
        final SongRepository repo = mock(SongRepository.class);
        when(repo.findAll()).thenReturn(List.of(seedSong("f1"), seedSong("f2")));
        final AudioAnalysisRunner runner = mock(AudioAnalysisRunner.class);
        when(runner.analyzeByMetadata(anyString(), anyString()))
                .thenThrow(new AudioAnalysisFailedException("test failure"));
        final SongAudioBackfillCommand cmd = new SongAudioBackfillCommand(repo, runner, new SimpleMeterRegistry());

        final SongAudioBackfillCommand.BackfillSummary summary = cmd.runBackfill(0.6);

        assertThat(summary.failed()).isEqualTo(2);
        assertThat(summary.successful()).isZero();
        assertThat(SongAudioBackfillCommand.getLastBackfillCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("순차 호출 시 monotonic 비감소 — 새 호출 시각이 이전 시각보다 작지 않다")
    void sequentialCalls_monotonicNonDecreasing() throws Exception {
        final SongRepository repo = mock(SongRepository.class);
        when(repo.findAll()).thenReturn(List.of());
        final AudioAnalysisRunner runner = mock(AudioAnalysisRunner.class);
        final SongAudioBackfillCommand cmd = new SongAudioBackfillCommand(repo, runner, new SimpleMeterRegistry());

        cmd.runBackfill(0.6);
        final Instant first = SongAudioBackfillCommand.getLastBackfillCompletedAt();
        // Instant.now() resolution 보장 위해 1ms sleep
        Thread.sleep(2L);
        cmd.runBackfill(0.6);
        final Instant second = SongAudioBackfillCommand.getLastBackfillCompletedAt();

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(second).isAfterOrEqualTo(first);
    }

    @Test
    @DisplayName("인스턴스가 달라도 정적 상태 공유 — 한쪽 호출이 다른쪽에서도 관측")
    void staticStateSharedAcrossInstances() {
        final SongRepository repo = mock(SongRepository.class);
        when(repo.findAll()).thenReturn(List.of());
        final AudioAnalysisRunner runner = mock(AudioAnalysisRunner.class);
        final SongAudioBackfillCommand instanceA = new SongAudioBackfillCommand(repo, runner,
                new SimpleMeterRegistry());
        final SongAudioBackfillCommand instanceB = new SongAudioBackfillCommand(repo, runner,
                new SimpleMeterRegistry());

        assertThat(SongAudioBackfillCommand.getLastBackfillCompletedAt()).isNull();
        instanceA.runBackfill(0.6);
        final Instant afterA = SongAudioBackfillCommand.getLastBackfillCompletedAt();
        assertThat(afterA).isNotNull();

        // instanceB 가 호출되기 전에도 instanceA 의 결과가 그대로 보임
        assertThat(SongAudioBackfillCommand.getLastBackfillCompletedAt()).isEqualTo(afterA);

        // instanceB 호출 후엔 instanceB 의 시각이 보임 (정적 상태 공유 확인)
        instanceB.runBackfill(0.6);
        assertThat(SongAudioBackfillCommand.getLastBackfillCompletedAt()).isAfterOrEqualTo(afterA);
    }

    @Test
    @DisplayName("호출 전후 wall-clock 으로 sandwich — 기록 시각이 [before, after] 범위 안")
    void timestampWithinWallClockSandwich() {
        final SongRepository repo = mock(SongRepository.class);
        when(repo.findAll()).thenReturn(List.of());
        final AudioAnalysisRunner runner = mock(AudioAnalysisRunner.class);
        final SongAudioBackfillCommand cmd = new SongAudioBackfillCommand(repo, runner, new SimpleMeterRegistry());

        final Instant before = Instant.now();
        cmd.runBackfill(0.6);
        final Instant after = Instant.now();

        final Instant recorded = SongAudioBackfillCommand.getLastBackfillCompletedAt();
        assertThat(recorded).isNotNull();
        assertThat(recorded).isAfterOrEqualTo(before);
        assertThat(recorded).isBeforeOrEqualTo(after);
    }

    @Test
    @DisplayName("getter 는 같은 reference 를 반복 반환 — set 호출이 없으면 값이 고정")
    void getterIsStableBetweenWrites() {
        final SongRepository repo = mock(SongRepository.class);
        when(repo.findAll()).thenReturn(List.of());
        final AudioAnalysisRunner runner = mock(AudioAnalysisRunner.class);
        final SongAudioBackfillCommand cmd = new SongAudioBackfillCommand(repo, runner, new SimpleMeterRegistry());

        cmd.runBackfill(0.6);
        final Instant first = SongAudioBackfillCommand.getLastBackfillCompletedAt();
        final Instant second = SongAudioBackfillCommand.getLastBackfillCompletedAt();
        final Instant third = SongAudioBackfillCommand.getLastBackfillCompletedAt();

        assertThat(first).isNotNull();
        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
    }
}
