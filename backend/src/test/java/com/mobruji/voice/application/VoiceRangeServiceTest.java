package com.mobruji.voice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mobruji.voice.domain.VoiceRange;
import com.mobruji.voice.domain.VoiceRangeNotFoundException;
import com.mobruji.voice.domain.VoiceRangeSourceMethod;
import com.mobruji.voice.infrastructure.VoiceRangeRepository;

@ExtendWith(MockitoExtension.class)
class VoiceRangeServiceTest {

    @Mock
    private VoiceRangeRepository voiceRangeRepository;

    @InjectMocks
    private VoiceRangeService voiceRangeService;

    @Test
    @DisplayName("createOrReplace: 신규 sessionId면 save 호출 후 도메인 객체 반환")
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
    }

    @Test
    @DisplayName("createOrReplace: 기존 sessionId면 update만 호출 (save 호출 안 함)")
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
    @DisplayName("updateBySessionId: 존재하면 업데이트 후 도메인 객체 반환")
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
    }

    @Test
    @DisplayName("updateBySessionId: 없으면 VoiceRangeNotFoundException")
    void updateBySessionId_notFound_throws() {
        given(voiceRangeRepository.findBySessionId("missing")).willReturn(Optional.empty());
        final UpdateVoiceRangeCommand command = new UpdateVoiceRangeCommand(
                50, 72, VoiceRangeSourceMethod.MIC_MEASURE);

        assertThatThrownBy(() -> voiceRangeService.updateBySessionId("missing", command))
                .isInstanceOf(VoiceRangeNotFoundException.class);
    }
}
