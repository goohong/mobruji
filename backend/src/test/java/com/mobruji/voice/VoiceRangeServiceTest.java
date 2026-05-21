package com.mobruji.voice;

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

import com.mobruji.voice.dto.VoiceRangeCreateRequest;
import com.mobruji.voice.dto.VoiceRangeResponse;
import com.mobruji.voice.dto.VoiceRangeUpdateRequest;

@ExtendWith(MockitoExtension.class)
class VoiceRangeServiceTest {

    @Mock
    private VoiceRangeRepository voiceRangeRepository;

    @InjectMocks
    private VoiceRangeService voiceRangeService;

    @Test
    @DisplayName("createOrReplace: 신규 sessionId면 save 호출 후 응답 반환")
    void createOrReplace_newSession_savesAndReturns() {
        // given
        final VoiceRangeCreateRequest request = new VoiceRangeCreateRequest(
                "new-session", 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK);
        given(voiceRangeRepository.findBySessionId("new-session")).willReturn(Optional.empty());
        final VoiceRange saved = VoiceRange.create("new-session", 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK);
        given(voiceRangeRepository.save(any(VoiceRange.class))).willReturn(saved);

        // when
        final VoiceRangeResponse voiceRangeResponse = voiceRangeService.createOrReplace(request);

        // then
        assertThat(voiceRangeResponse.sessionId()).isEqualTo("new-session");
        assertThat(voiceRangeResponse.lowestNoteMidi()).isEqualTo(48);
        verify(voiceRangeRepository).save(any(VoiceRange.class));
    }

    @Test
    @DisplayName("createOrReplace: 기존 sessionId면 update만 호출 (save 호출 안 함)")
    void createOrReplace_existingSession_updatesInPlace() {
        // given
        final VoiceRange existing = VoiceRange.create("dup-session", 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK);
        given(voiceRangeRepository.findBySessionId("dup-session")).willReturn(Optional.of(existing));
        final VoiceRangeCreateRequest request = new VoiceRangeCreateRequest(
                "dup-session", 50, 72, VoiceRangeSourceMethod.MIC_MEASURE);

        // when
        final VoiceRangeResponse voiceRangeResponse = voiceRangeService.createOrReplace(request);

        // then
        assertThat(voiceRangeResponse.lowestNoteMidi()).isEqualTo(50);
        assertThat(voiceRangeResponse.highestNoteMidi()).isEqualTo(72);
        assertThat(voiceRangeResponse.sourceMethod()).isEqualTo(VoiceRangeSourceMethod.MIC_MEASURE);
        verify(voiceRangeRepository, never()).save(any());
    }

    @Test
    @DisplayName("readBySessionId: 존재하면 응답 반환")
    void readBySessionId_found_returnsResponse() {
        // given
        final VoiceRange existing = VoiceRange.create("s", 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK);
        given(voiceRangeRepository.findBySessionId("s")).willReturn(Optional.of(existing));

        // when
        final VoiceRangeResponse voiceRangeResponse = voiceRangeService.readBySessionId("s");

        // then
        assertThat(voiceRangeResponse.sessionId()).isEqualTo("s");
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
    @DisplayName("updateBySessionId: 존재하면 업데이트 후 응답 반환")
    void updateBySessionId_found_updates() {
        // given
        final VoiceRange existing = VoiceRange.create("s", 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK);
        given(voiceRangeRepository.findBySessionId("s")).willReturn(Optional.of(existing));
        final VoiceRangeUpdateRequest request = new VoiceRangeUpdateRequest(
                50, 72, VoiceRangeSourceMethod.MIC_MEASURE);

        // when
        final VoiceRangeResponse voiceRangeResponse = voiceRangeService.updateBySessionId("s", request);

        // then
        assertThat(voiceRangeResponse.lowestNoteMidi()).isEqualTo(50);
        assertThat(voiceRangeResponse.highestNoteMidi()).isEqualTo(72);
    }

    @Test
    @DisplayName("updateBySessionId: 없으면 VoiceRangeNotFoundException")
    void updateBySessionId_notFound_throws() {
        given(voiceRangeRepository.findBySessionId("missing")).willReturn(Optional.empty());
        final VoiceRangeUpdateRequest request = new VoiceRangeUpdateRequest(
                50, 72, VoiceRangeSourceMethod.MIC_MEASURE);

        assertThatThrownBy(() -> voiceRangeService.updateBySessionId("missing", request))
                .isInstanceOf(VoiceRangeNotFoundException.class);
    }
}
