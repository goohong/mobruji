package com.mobruji.voice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mobruji.voice.domain.VoiceRange;
import com.mobruji.voice.domain.VoiceRangeNotFoundException;
import com.mobruji.voice.domain.VoiceRangeSnapshot;
import com.mobruji.voice.domain.VoiceRangeSourceMethod;
import com.mobruji.voice.infrastructure.VoiceRangeRepository;
import com.mobruji.voice.infrastructure.VoiceRangeSnapshotRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
class VoiceRangeServiceTest {

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
    @DisplayName("createOrReplace: 신규 sessionId면 save 호출 후 도메인 객체 반환 + snapshot 1행 insert")
    void createOrReplace_newSession_savesAndReturns() {
        // given
        final CreateVoiceRangeCommand command = new CreateVoiceRangeCommand(
                "new-session", 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK);
        given(voiceRangeRepository.findBySessionId("new-session")).willReturn(Optional.empty());
        final VoiceRange saved = VoiceRange.create("new-session", 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK);
        given(voiceRangeRepository.save(any(VoiceRange.class))).willReturn(saved);

        // when
        final VoiceRange voiceRange = voiceRangeService.createOrReplace(command);

        // then
        assertThat(voiceRange.getSessionId()).isEqualTo("new-session");
        assertThat(voiceRange.getLowestNoteMidi()).isEqualTo(48);
        verify(voiceRangeRepository).save(any(VoiceRange.class));
        final ArgumentCaptor<VoiceRangeSnapshot> snapshotCaptor = ArgumentCaptor.forClass(VoiceRangeSnapshot.class);
        verify(voiceRangeSnapshotRepository).save(snapshotCaptor.capture());
        final VoiceRangeSnapshot snapshot = snapshotCaptor.getValue();
        assertThat(snapshot.getSessionId()).isEqualTo("new-session");
        assertThat(snapshot.getLowMidi()).isEqualTo(48);
        assertThat(snapshot.getHighMidi()).isEqualTo(69);
        assertThat(snapshot.getSourceMethod()).isEqualTo(VoiceRangeSourceMethod.OCTAVE_PICK);
        assertThat(snapshot.getMeasuredAt()).isNotNull();
    }

    @Test
    @DisplayName("createOrReplace: 기존 sessionId면 update만 호출 (voiceRange.save 없음) + snapshot 1행 insert")
    void createOrReplace_existingSession_updatesInPlace() {
        // given
        final VoiceRange existing = VoiceRange.create("dup-session", 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK);
        given(voiceRangeRepository.findBySessionId("dup-session")).willReturn(Optional.of(existing));
        final CreateVoiceRangeCommand command = new CreateVoiceRangeCommand(
                "dup-session", 50, 72, VoiceRangeSourceMethod.MIC_MEASURE);

        // when
        final VoiceRange voiceRange = voiceRangeService.createOrReplace(command);

        // then
        assertThat(voiceRange.getLowestNoteMidi()).isEqualTo(50);
        assertThat(voiceRange.getHighestNoteMidi()).isEqualTo(72);
        assertThat(voiceRange.getSourceMethod()).isEqualTo(VoiceRangeSourceMethod.MIC_MEASURE);
        verify(voiceRangeRepository, never()).save(any());
        final ArgumentCaptor<VoiceRangeSnapshot> snapshotCaptor = ArgumentCaptor.forClass(VoiceRangeSnapshot.class);
        verify(voiceRangeSnapshotRepository).save(snapshotCaptor.capture());
        final VoiceRangeSnapshot snapshot = snapshotCaptor.getValue();
        assertThat(snapshot.getLowMidi()).isEqualTo(50);
        assertThat(snapshot.getHighMidi()).isEqualTo(72);
        assertThat(snapshot.getSourceMethod()).isEqualTo(VoiceRangeSourceMethod.MIC_MEASURE);
    }

    @Test
    @DisplayName("readBySessionId: 존재하면 도메인 객체 반환")
    void readBySessionId_found_returnsResponse() {
        // given
        final VoiceRange existing = VoiceRange.create("s", 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK);
        given(voiceRangeRepository.findBySessionId("s")).willReturn(Optional.of(existing));

        // when
        final VoiceRange voiceRange = voiceRangeService.readBySessionId("s");

        // then
        assertThat(voiceRange.getSessionId()).isEqualTo("s");
        verify(voiceRangeSnapshotRepository, never()).save(any());
    }

    @Test
    @DisplayName("readBySessionId: 없으면 VoiceRangeNotFoundException")
    void readBySessionId_notFound_throws() {
        given(voiceRangeRepository.findBySessionId("missing")).willReturn(Optional.empty());

        assertThatThrownBy(() -> voiceRangeService.readBySessionId("missing"))
                .isInstanceOf(VoiceRangeNotFoundException.class)
                .hasMessageContaining("missing");
    }

    @Test
    @DisplayName("updateBySessionId: 존재하면 업데이트 후 도메인 객체 반환 + snapshot 1행 insert")
    void updateBySessionId_found_updates() {
        // given
        final VoiceRange existing = VoiceRange.create("s", 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK);
        given(voiceRangeRepository.findBySessionId("s")).willReturn(Optional.of(existing));
        final UpdateVoiceRangeCommand command = new UpdateVoiceRangeCommand(
                50, 72, VoiceRangeSourceMethod.MIC_MEASURE);

        // when
        final VoiceRange voiceRange = voiceRangeService.updateBySessionId("s", command);

        // then
        assertThat(voiceRange.getLowestNoteMidi()).isEqualTo(50);
        assertThat(voiceRange.getHighestNoteMidi()).isEqualTo(72);
        final ArgumentCaptor<VoiceRangeSnapshot> snapshotCaptor = ArgumentCaptor.forClass(VoiceRangeSnapshot.class);
        verify(voiceRangeSnapshotRepository).save(snapshotCaptor.capture());
        assertThat(snapshotCaptor.getValue().getLowMidi()).isEqualTo(50);
        assertThat(snapshotCaptor.getValue().getHighMidi()).isEqualTo(72);
    }

    @Test
    @DisplayName("updateBySessionId: 없으면 VoiceRangeNotFoundException — snapshot insert 없음")
    void updateBySessionId_notFound_throws() {
        given(voiceRangeRepository.findBySessionId("missing")).willReturn(Optional.empty());
        final UpdateVoiceRangeCommand command = new UpdateVoiceRangeCommand(
                50, 72, VoiceRangeSourceMethod.MIC_MEASURE);

        assertThatThrownBy(() -> voiceRangeService.updateBySessionId("missing", command))
                .isInstanceOf(VoiceRangeNotFoundException.class);
        verify(voiceRangeSnapshotRepository, never()).save(any());
    }

    @Test
    @DisplayName("createOrReplace: 동일 sessionId 연속 호출 시 save 1회 + snapshot 2회 (idempotent upsert)")
    void createOrReplace_calledTwiceForSameSession_savesOnceSnapshotsTwice() {
        // given — 첫 호출은 신규, 두 번째 호출은 동일 도메인 객체 재사용
        final VoiceRange persisted = VoiceRange.create("idem", 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK);
        given(voiceRangeRepository.findBySessionId("idem"))
                .willReturn(Optional.empty())
                .willReturn(Optional.of(persisted));
        given(voiceRangeRepository.save(any(VoiceRange.class))).willReturn(persisted);
        final CreateVoiceRangeCommand first = new CreateVoiceRangeCommand(
                "idem", 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK);
        final CreateVoiceRangeCommand second = new CreateVoiceRangeCommand(
                "idem", 50, 72, VoiceRangeSourceMethod.MIC_MEASURE);

        // when
        voiceRangeService.createOrReplace(first);
        final VoiceRange voiceRange = voiceRangeService.createOrReplace(second);

        // then
        assertThat(voiceRange.getLowestNoteMidi()).isEqualTo(50);
        assertThat(voiceRange.getHighestNoteMidi()).isEqualTo(72);
        verify(voiceRangeRepository, times(1)).save(any(VoiceRange.class));
        verify(voiceRangeSnapshotRepository, times(2)).save(any(VoiceRangeSnapshot.class));
    }

    @Test
    @DisplayName("createOrReplace: 신규 경로 snapshot이 fromVoiceRange로 saved VoiceRange와 동일 필드 가짐")
    void createOrReplace_newSession_snapshotMirrorsSavedVoiceRange() {
        // given
        final CreateVoiceRangeCommand command = new CreateVoiceRangeCommand(
                "mirror", 52, 76, VoiceRangeSourceMethod.MIC_MEASURE);
        given(voiceRangeRepository.findBySessionId("mirror")).willReturn(Optional.empty());
        final VoiceRange saved = VoiceRange.create("mirror", 52, 76, VoiceRangeSourceMethod.MIC_MEASURE);
        given(voiceRangeRepository.save(any(VoiceRange.class))).willReturn(saved);

        // when
        voiceRangeService.createOrReplace(command);

        // then — snapshot은 saved의 sessionId·low·high·sourceMethod를 그대로 복제
        final ArgumentCaptor<VoiceRangeSnapshot> snapshotCaptor = ArgumentCaptor.forClass(VoiceRangeSnapshot.class);
        verify(voiceRangeSnapshotRepository).save(snapshotCaptor.capture());
        final VoiceRangeSnapshot snapshot = snapshotCaptor.getValue();
        assertThat(snapshot.getSessionId()).isEqualTo(saved.getSessionId());
        assertThat(snapshot.getLowMidi()).isEqualTo(saved.getLowestNoteMidi());
        assertThat(snapshot.getHighMidi()).isEqualTo(saved.getHighestNoteMidi());
        assertThat(snapshot.getSourceMethod()).isEqualTo(saved.getSourceMethod());
    }

    @Test
    @DisplayName("updateBySessionId: snapshot이 update 적용된 후 상태 기준으로 생성 (이전 값 아님)")
    void updateBySessionId_snapshotReflectsPostUpdateState() {
        // given — 기존 voiceRange는 48/69, 업데이트 명령은 55/80
        final VoiceRange existing = VoiceRange.create("post", 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK);
        given(voiceRangeRepository.findBySessionId("post")).willReturn(Optional.of(existing));
        final UpdateVoiceRangeCommand command = new UpdateVoiceRangeCommand(
                55, 80, VoiceRangeSourceMethod.MIC_MEASURE);

        // when
        voiceRangeService.updateBySessionId("post", command);

        // then — snapshot은 update 후 값 (55/80, MIC_MEASURE)을 보유
        final ArgumentCaptor<VoiceRangeSnapshot> snapshotCaptor = ArgumentCaptor.forClass(VoiceRangeSnapshot.class);
        verify(voiceRangeSnapshotRepository).save(snapshotCaptor.capture());
        final VoiceRangeSnapshot snapshot = snapshotCaptor.getValue();
        assertThat(snapshot.getLowMidi()).isEqualTo(55);
        assertThat(snapshot.getHighMidi()).isEqualTo(80);
        assertThat(snapshot.getSourceMethod()).isEqualTo(VoiceRangeSourceMethod.MIC_MEASURE);
    }

    @Test
    @DisplayName("readHistoryBySessionId: 빈 결과면 빈 리스트 반환 — snapshot save 미호출")
    void readHistoryBySessionId_empty_returnsEmptyList() {
        // given
        given(voiceRangeSnapshotRepository.findBySessionIdOrderByMeasuredAtAsc("none"))
                .willReturn(List.of());

        // when
        final List<VoiceRangeSnapshot> snapshots = voiceRangeService.readHistoryBySessionId("none");

        // then
        assertThat(snapshots).isEmpty();
        verify(voiceRangeSnapshotRepository, never()).save(any());
    }

    @Test
    @DisplayName("readHistoryBySessionId: 리포지토리가 반환한 순서를 그대로 전달")
    void readHistoryBySessionId_returnsRepositoryOrderUnchanged() {
        // given
        final VoiceRange v1 = VoiceRange.create("hist", 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK);
        final VoiceRange v2 = VoiceRange.create("hist", 50, 72, VoiceRangeSourceMethod.MIC_MEASURE);
        final VoiceRangeSnapshot first = VoiceRangeSnapshot.fromVoiceRange(v1);
        final VoiceRangeSnapshot second = VoiceRangeSnapshot.fromVoiceRange(v2);
        given(voiceRangeSnapshotRepository.findBySessionIdOrderByMeasuredAtAsc("hist"))
                .willReturn(List.of(first, second));

        // when
        final List<VoiceRangeSnapshot> snapshots = voiceRangeService.readHistoryBySessionId("hist");

        // then — 순서/내용 변형 없이 그대로 통과
        assertThat(snapshots).containsExactly(first, second);
    }

    @Test
    @DisplayName("readHistoryBySessionId: snapshot이 1건이면 단일 원소 리스트 그대로 반환")
    void readHistoryBySessionId_singleSnapshot_returnsSingletonList() {
        // given
        final VoiceRangeSnapshot single = VoiceRangeSnapshot.fromVoiceRange(
                VoiceRange.create("one", 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK));
        given(voiceRangeSnapshotRepository.findBySessionIdOrderByMeasuredAtAsc("one"))
                .willReturn(List.of(single));

        // when
        final List<VoiceRangeSnapshot> snapshots = voiceRangeService.readHistoryBySessionId("one");

        // then
        assertThat(snapshots).hasSize(1).containsExactly(single);
    }

    @Test
    @DisplayName("readHistoryBySessionId: 인자 sessionId를 그대로 repository에 전달 (변형 없음)")
    void readHistoryBySessionId_passesSessionIdUnchanged() {
        // given
        final ArgumentCaptor<String> sessionIdCaptor = ArgumentCaptor.forClass(String.class);
        given(voiceRangeSnapshotRepository.findBySessionIdOrderByMeasuredAtAsc(sessionIdCaptor.capture()))
                .willReturn(List.of());

        // when
        voiceRangeService.readHistoryBySessionId("session-xyz-123");

        // then — 트리밍·소문자화 등 어떤 변형도 없이 그대로 전달
        assertThat(sessionIdCaptor.getValue()).isEqualTo("session-xyz-123");
    }

    @Test
    @DisplayName("readHistoryBySessionId: read-only — voiceRangeRepository.save / snapshotRepository.save 어느 쪽도 호출 안 함")
    void readHistoryBySessionId_isReadOnly_neverInvokesSave() {
        // given
        given(voiceRangeSnapshotRepository.findBySessionIdOrderByMeasuredAtAsc("ro"))
                .willReturn(List.of(VoiceRangeSnapshot.fromVoiceRange(
                        VoiceRange.create("ro", 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK))));

        // when
        voiceRangeService.readHistoryBySessionId("ro");

        // then — write-side 격리: 양쪽 repository 모두 save 호출 없음
        verify(voiceRangeRepository, never()).save(any());
        verify(voiceRangeSnapshotRepository, never()).save(any());
    }

    @Test
    @DisplayName("readHistoryBySessionId: 반환된 snapshot의 필드(low/high/sourceMethod/sessionId)가 repository 결과 그대로")
    void readHistoryBySessionId_preservesSnapshotFields() {
        // given
        final VoiceRangeSnapshot snapshot = VoiceRangeSnapshot.fromVoiceRange(
                VoiceRange.create("fields", 52, 76, VoiceRangeSourceMethod.MIC_MEASURE));
        given(voiceRangeSnapshotRepository.findBySessionIdOrderByMeasuredAtAsc("fields"))
                .willReturn(List.of(snapshot));

        // when
        final List<VoiceRangeSnapshot> snapshots = voiceRangeService.readHistoryBySessionId("fields");

        // then — 필드 값이 변형 없이 노출
        assertThat(snapshots).hasSize(1);
        final VoiceRangeSnapshot got = snapshots.get(0);
        assertThat(got.getSessionId()).isEqualTo("fields");
        assertThat(got.getLowMidi()).isEqualTo(52);
        assertThat(got.getHighMidi()).isEqualTo(76);
        assertThat(got.getSourceMethod()).isEqualTo(VoiceRangeSourceMethod.MIC_MEASURE);
    }

    @Test
    @DisplayName("readHistoryBySessionId: 서로 다른 sessionId 호출은 각각 격리된 결과를 반환 (cross-session leak 없음)")
    void readHistoryBySessionId_isolatesPerSession() {
        // given
        final VoiceRangeSnapshot snapshotA = VoiceRangeSnapshot.fromVoiceRange(
                VoiceRange.create("session-a", 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK));
        final VoiceRangeSnapshot snapshotB = VoiceRangeSnapshot.fromVoiceRange(
                VoiceRange.create("session-b", 55, 80, VoiceRangeSourceMethod.MIC_MEASURE));
        given(voiceRangeSnapshotRepository.findBySessionIdOrderByMeasuredAtAsc("session-a"))
                .willReturn(List.of(snapshotA));
        given(voiceRangeSnapshotRepository.findBySessionIdOrderByMeasuredAtAsc("session-b"))
                .willReturn(List.of(snapshotB));

        // when
        final List<VoiceRangeSnapshot> resultA = voiceRangeService.readHistoryBySessionId("session-a");
        final List<VoiceRangeSnapshot> resultB = voiceRangeService.readHistoryBySessionId("session-b");

        // then — 각 sessionId 결과가 서로 섞이지 않음
        assertThat(resultA).containsExactly(snapshotA);
        assertThat(resultB).containsExactly(snapshotB);
        assertThat(resultA).doesNotContain(snapshotB);
        assertThat(resultB).doesNotContain(snapshotA);
    }
}
