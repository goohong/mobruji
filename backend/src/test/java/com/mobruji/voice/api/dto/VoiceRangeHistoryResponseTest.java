package com.mobruji.voice.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.voice.domain.VoiceRangeSnapshot;
import com.mobruji.voice.domain.VoiceRangeSourceMethod;

/**
 * {@link VoiceRangeHistoryResponse} wrapper 회귀 가드.
 *
 * <p>spec: voice-range-progress.md §5-2 — 향후 페이지네이션/메타 필드 추가 여지를 위해 배열을 직접 노출하지 않고 wrapper에 담는다.
 * 필드명 (`voiceRangeSnapshotResponses`)이 fe contract이므로 record component 이름이 바뀌면 본 테스트가 잡는다.
 */
class VoiceRangeHistoryResponseTest {

    @Test
    @DisplayName("voiceRangeSnapshotResponses 리스트를 wrap한다")
    void wraps_snapshotResponseList() {
        // given
        final VoiceRangeSnapshot voiceRangeSnapshot = VoiceRangeSnapshot.create(
                "s-1", 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK);
        final VoiceRangeSnapshotResponse voiceRangeSnapshotResponse = VoiceRangeSnapshotResponse.from(
                voiceRangeSnapshot);

        // when
        final VoiceRangeHistoryResponse voiceRangeHistoryResponse = new VoiceRangeHistoryResponse(
                List.of(voiceRangeSnapshotResponse));

        // then
        assertThat(voiceRangeHistoryResponse.voiceRangeSnapshotResponses()).hasSize(1);
        assertThat(voiceRangeHistoryResponse.voiceRangeSnapshotResponses().get(0).lowMidi()).isEqualTo(48);
    }

    @Test
    @DisplayName("빈 리스트도 허용 (history 없음)")
    void wraps_emptyList() {
        final VoiceRangeHistoryResponse voiceRangeHistoryResponse = new VoiceRangeHistoryResponse(List.of());
        assertThat(voiceRangeHistoryResponse.voiceRangeSnapshotResponses()).isEmpty();
    }
}
