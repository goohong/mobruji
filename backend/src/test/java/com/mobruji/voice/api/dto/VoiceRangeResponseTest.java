package com.mobruji.voice.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.voice.domain.VoiceRange;
import com.mobruji.voice.domain.VoiceRangeSourceMethod;

class VoiceRangeResponseTest {

    @Test
    @DisplayName("from: VoiceRange 도메인 객체를 응답 DTO로 변환")
    void from_mapsDomainToDto() {
        // given
        final VoiceRange voiceRange = VoiceRange.create("s-1", 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK);

        // when
        final VoiceRangeResponse voiceRangeResponse = VoiceRangeResponse.from(voiceRange);

        // then
        assertThat(voiceRangeResponse.sessionId()).isEqualTo("s-1");
        assertThat(voiceRangeResponse.lowestNoteMidi()).isEqualTo(48);
        assertThat(voiceRangeResponse.highestNoteMidi()).isEqualTo(69);
        assertThat(voiceRangeResponse.sourceMethod()).isEqualTo(VoiceRangeSourceMethod.OCTAVE_PICK);
        assertThat(voiceRangeResponse.createdAt()).isNotNull();
        assertThat(voiceRangeResponse.updatedAt()).isNotNull();
    }
}
