package com.mobruji.voice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mobruji.voice.domain.VoiceRange;
import com.mobruji.voice.domain.VoiceRangeNotFoundException;
import com.mobruji.voice.domain.VoiceRangeSourceMethod;
import com.mobruji.voice.infrastructure.VoiceRangeRepository;
import com.mobruji.voice.infrastructure.VoiceRangeSnapshotRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * spec: {@code docs/features/voice-range-progress.md} §3 비기능 — snapshot insert 시
 * {@code mobruji.voice.range.snapshot.inserted} 카운터 +1 보장. spec
 * {@code docs/features/observability-baseline.md} §5-3 단일 진실 정합.
 */
@ExtendWith(MockitoExtension.class)
class VoiceRangeServiceMetricsTest {

    private static final String METRIC_SNAPSHOT_INSERTED = "mobruji.voice.range.snapshot.inserted";

    @Mock
    private VoiceRangeRepository voiceRangeRepository;

    @Mock
    private VoiceRangeSnapshotRepository voiceRangeSnapshotRepository;

    private SimpleMeterRegistry meterRegistry;
    private VoiceRangeService voiceRangeService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        voiceRangeService = new VoiceRangeService(
                voiceRangeRepository, voiceRangeSnapshotRepository, meterRegistry);
    }

    @Test
    @DisplayName("createOrReplace 신규 경로: snapshot insert 후 counter +1")
    void createOrReplace_newSession_incrementsCounterOnce() {
        // given
        final CreateVoiceRangeCommand command = new CreateVoiceRangeCommand(
                "session-a", 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK);
        given(voiceRangeRepository.findBySessionId("session-a")).willReturn(Optional.empty());
        final VoiceRange saved = VoiceRange.create(
                "session-a", 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK);
        given(voiceRangeRepository.save(any(VoiceRange.class))).willReturn(saved);

        // when
        voiceRangeService.createOrReplace(command);

        // then
        assertThat(snapshotCounterValue()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("createOrReplace 기존 경로: snapshot insert 후 counter +1")
    void createOrReplace_existingSession_incrementsCounterOnce() {
        // given
        final VoiceRange existing = VoiceRange.create(
                "session-b", 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK);
        given(voiceRangeRepository.findBySessionId("session-b")).willReturn(Optional.of(existing));
        final CreateVoiceRangeCommand command = new CreateVoiceRangeCommand(
                "session-b", 50, 72, VoiceRangeSourceMethod.MIC_MEASURE);

        // when
        voiceRangeService.createOrReplace(command);

        // then
        assertThat(snapshotCounterValue()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("updateBySessionId: snapshot insert 후 counter +1")
    void updateBySessionId_incrementsCounterOnce() {
        // given
        final VoiceRange existing = VoiceRange.create(
                "session-c", 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK);
        given(voiceRangeRepository.findBySessionId("session-c")).willReturn(Optional.of(existing));
        final UpdateVoiceRangeCommand command = new UpdateVoiceRangeCommand(
                50, 72, VoiceRangeSourceMethod.MIC_MEASURE);

        // when
        voiceRangeService.updateBySessionId("session-c", command);

        // then
        assertThat(snapshotCounterValue()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("연속 호출 시 counter 가 호출 수만큼 누적 (3건)")
    void multipleInvocations_counterAccumulates() {
        // given
        final VoiceRange persisted = VoiceRange.create(
                "session-d", 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK);
        given(voiceRangeRepository.findBySessionId("session-d"))
                .willReturn(Optional.empty())
                .willReturn(Optional.of(persisted))
                .willReturn(Optional.of(persisted));
        given(voiceRangeRepository.save(any(VoiceRange.class))).willReturn(persisted);

        // when
        voiceRangeService.createOrReplace(new CreateVoiceRangeCommand(
                "session-d", 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK));
        voiceRangeService.createOrReplace(new CreateVoiceRangeCommand(
                "session-d", 50, 72, VoiceRangeSourceMethod.MIC_MEASURE));
        voiceRangeService.updateBySessionId("session-d", new UpdateVoiceRangeCommand(
                52, 74, VoiceRangeSourceMethod.MIC_MEASURE));

        // then
        assertThat(snapshotCounterValue()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("updateBySessionId: sessionId 미존재 시 counter 미증가 (NotFound 분기)")
    void updateBySessionId_notFound_doesNotIncrement() {
        given(voiceRangeRepository.findBySessionId("missing")).willReturn(Optional.empty());
        final UpdateVoiceRangeCommand command = new UpdateVoiceRangeCommand(
                50, 72, VoiceRangeSourceMethod.MIC_MEASURE);

        assertThatThrownBy(() -> voiceRangeService.updateBySessionId("missing", command))
                .isInstanceOf(VoiceRangeNotFoundException.class);
        assertThat(snapshotCounterValue()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("createOrReplace 신규 경로: 검증 실패(low>high) 시 counter 미증가")
    void createOrReplace_invalidRange_doesNotIncrement() {
        // given — low(70) > high(60) 으로 도메인 검증 실패
        given(voiceRangeRepository.findBySessionId("invalid-session"))
                .willReturn(Optional.empty());
        final CreateVoiceRangeCommand command = new CreateVoiceRangeCommand(
                "invalid-session", 70, 60, VoiceRangeSourceMethod.OCTAVE_PICK);

        // when / then
        assertThatThrownBy(() -> voiceRangeService.createOrReplace(command))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(snapshotCounterValue()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("readBySessionId / readHistoryBySessionId: read-only — counter 미증가")
    void readPaths_doNotIncrement() {
        // given
        given(voiceRangeRepository.findBySessionId("read-only"))
                .willReturn(Optional.of(VoiceRange.create(
                        "read-only", 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK)));

        // when
        voiceRangeService.readBySessionId("read-only");
        voiceRangeService.readHistoryBySessionId("read-only");

        // then
        assertThat(snapshotCounterValue()).isEqualTo(0.0);
    }

    private double snapshotCounterValue() {
        final Counter counter = meterRegistry.find(METRIC_SNAPSHOT_INSERTED).counter();
        if (counter == null) {
            return 0.0;
        }
        return counter.count();
    }
}
