package com.mobruji.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.mobruji.voice.domain.VoiceRangeSnapshot;
import com.mobruji.voice.domain.VoiceRangeSourceMethod;
import com.mobruji.voice.infrastructure.VoiceRangeSnapshotRepository;

/**
 * VoiceRangeSnapshotRepository 정렬/필터 검증.
 *
 * <p>spec: docs/features/voice-range-progress.md §7 — 통합: 정렬/필터 (measuredAt ASC/DESC).
 */
@Transactional
@SpringBootTest
@ActiveProfiles("test")
class VoiceRangeSnapshotRepositoryIntegrationTest {

    @Autowired
    private VoiceRangeSnapshotRepository voiceRangeSnapshotRepository;

    @BeforeEach
    void setUp() {
        voiceRangeSnapshotRepository.deleteAll();
    }

    @Test
    @DisplayName("findBySessionIdOrderByMeasuredAtDesc: 같은 sessionId 의 행을 measuredAt 내림차순으로 반환")
    void findBySessionIdOrderByMeasuredAtDesc_returnsRowsInDescOrder() throws InterruptedException {
        // given
        final String sessionId = "repo-session-1";
        voiceRangeSnapshotRepository.save(
                VoiceRangeSnapshot.create(sessionId, 48, 64, VoiceRangeSourceMethod.OCTAVE_PICK));
        Thread.sleep(5);
        voiceRangeSnapshotRepository.save(
                VoiceRangeSnapshot.create(sessionId, 48, 66, VoiceRangeSourceMethod.OCTAVE_PICK));
        Thread.sleep(5);
        voiceRangeSnapshotRepository.save(
                VoiceRangeSnapshot.create(sessionId, 50, 69, VoiceRangeSourceMethod.MIC_MEASURE));

        // when
        final List<VoiceRangeSnapshot> voiceRangeSnapshotResponses = voiceRangeSnapshotRepository
                .findBySessionIdOrderByMeasuredAtDesc(sessionId);

        // then
        assertThat(voiceRangeSnapshotResponses).hasSize(3);
        assertThat(voiceRangeSnapshotResponses.get(0).getHighMidi()).isEqualTo(69);
        assertThat(voiceRangeSnapshotResponses.get(1).getHighMidi()).isEqualTo(66);
        assertThat(voiceRangeSnapshotResponses.get(2).getHighMidi()).isEqualTo(64);
        // 내림차순: measuredAt 단조 감소
        assertThat(voiceRangeSnapshotResponses.get(0).getMeasuredAt())
                .isAfterOrEqualTo(voiceRangeSnapshotResponses.get(1).getMeasuredAt());
        assertThat(voiceRangeSnapshotResponses.get(1).getMeasuredAt())
                .isAfterOrEqualTo(voiceRangeSnapshotResponses.get(2).getMeasuredAt());
    }

    @Test
    @DisplayName("findBySessionIdOrderByMeasuredAtAsc: 같은 sessionId 의 행을 measuredAt 오름차순으로 반환")
    void findBySessionIdOrderByMeasuredAtAsc_returnsRowsInAscOrder() throws InterruptedException {
        // given
        final String sessionId = "repo-session-asc";
        voiceRangeSnapshotRepository.save(
                VoiceRangeSnapshot.create(sessionId, 48, 64, VoiceRangeSourceMethod.OCTAVE_PICK));
        Thread.sleep(5);
        voiceRangeSnapshotRepository.save(
                VoiceRangeSnapshot.create(sessionId, 50, 70, VoiceRangeSourceMethod.MIC_MEASURE));

        // when
        final List<VoiceRangeSnapshot> voiceRangeSnapshotResponses = voiceRangeSnapshotRepository
                .findBySessionIdOrderByMeasuredAtAsc(sessionId);

        // then
        assertThat(voiceRangeSnapshotResponses).hasSize(2);
        assertThat(voiceRangeSnapshotResponses.get(0).getHighMidi()).isEqualTo(64);
        assertThat(voiceRangeSnapshotResponses.get(1).getHighMidi()).isEqualTo(70);
    }

    @Test
    @DisplayName("findBySessionIdOrderByMeasuredAtDesc: 다른 sessionId 행은 제외")
    void findBySessionIdOrderByMeasuredAtDesc_filtersOtherSessions() {
        // given
        voiceRangeSnapshotRepository.save(
                VoiceRangeSnapshot.create("s-a", 48, 64, VoiceRangeSourceMethod.OCTAVE_PICK));
        voiceRangeSnapshotRepository.save(
                VoiceRangeSnapshot.create("s-b", 50, 70, VoiceRangeSourceMethod.MIC_MEASURE));

        // when
        final List<VoiceRangeSnapshot> voiceRangeSnapshotResponses = voiceRangeSnapshotRepository
                .findBySessionIdOrderByMeasuredAtDesc("s-a");

        // then
        assertThat(voiceRangeSnapshotResponses).hasSize(1);
        assertThat(voiceRangeSnapshotResponses.get(0).getSessionId()).isEqualTo("s-a");
    }
}
