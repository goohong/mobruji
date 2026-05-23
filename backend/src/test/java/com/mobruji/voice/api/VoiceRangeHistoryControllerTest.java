package com.mobruji.voice.api;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.mobruji.auth.SessionAuthGuard;
import com.mobruji.voice.application.VoiceRangeService;
import com.mobruji.voice.domain.VoiceRangeSnapshot;
import com.mobruji.voice.domain.VoiceRangeSourceMethod;

/**
 * {@link VoiceRangeHistoryController} MockMvc 슬라이스 가드.
 *
 * <p>spec: docs/features/voice-range-progress.md §5-2. 통합 테스트({@code VoiceRangeHistoryIntegrationTest})는
 * full context 부팅이 필요해 회귀 감지 비용이 크다. 본 슬라이스 테스트는 컨트롤러 + {@link SessionAuthGuard}
 * 와이어링과 직렬화 회귀를 빠르게 잡는 용도.
 *
 * <p>실제 {@code SessionAuthGuard} 를 {@link Import} 해 401 가드 동작도 함께 검증한다.
 */
@WebMvcTest(VoiceRangeHistoryController.class)
@Import(SessionAuthGuard.class)
@ActiveProfiles("test")
class VoiceRangeHistoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VoiceRangeService voiceRangeService;

    @Test
    @DisplayName("GET history: snapshot 없으면 200 + 빈 배열")
    void readHistory_empty_returns200WithEmptyArray() throws Exception {
        // given
        given(voiceRangeService.readHistoryBySessionId("s-empty")).willReturn(List.of());

        // when / then
        mockMvc.perform(get("/api/v1/sessions/{sessionId}/voice-range-history", "s-empty")
                .header("X-Session-Id", "s-empty"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voiceRangeSnapshotResponses", hasSize(0)));
    }

    @Test
    @DisplayName("GET history: snapshot 2건이면 200 + 응답 매핑(lowMidi/highMidi/noteName/sourceMethod)")
    void readHistory_nonEmpty_returns200WithMappedFields() throws Exception {
        // given
        final VoiceRangeSnapshot first = VoiceRangeSnapshot.create(
                "s-multi", 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK);
        final VoiceRangeSnapshot second = VoiceRangeSnapshot.create(
                "s-multi", 50, 72, VoiceRangeSourceMethod.MIC_MEASURE);
        given(voiceRangeService.readHistoryBySessionId("s-multi")).willReturn(List.of(first, second));

        // when / then
        mockMvc.perform(get("/api/v1/sessions/{sessionId}/voice-range-history", "s-multi")
                .header("X-Session-Id", "s-multi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voiceRangeSnapshotResponses", hasSize(2)))
                .andExpect(jsonPath("$.voiceRangeSnapshotResponses[0].lowMidi", equalTo(48)))
                .andExpect(jsonPath("$.voiceRangeSnapshotResponses[0].highMidi", equalTo(69)))
                .andExpect(jsonPath("$.voiceRangeSnapshotResponses[0].lowestNoteName", equalTo("C3")))
                .andExpect(jsonPath("$.voiceRangeSnapshotResponses[0].highestNoteName", equalTo("A4")))
                .andExpect(jsonPath("$.voiceRangeSnapshotResponses[0].sourceMethod", equalTo("OCTAVE_PICK")))
                .andExpect(jsonPath("$.voiceRangeSnapshotResponses[1].lowMidi", equalTo(50)))
                .andExpect(jsonPath("$.voiceRangeSnapshotResponses[1].sourceMethod", equalTo("MIC_MEASURE")));
    }

    @Test
    @DisplayName("GET history: X-Session-Id 헤더 누락 → 401")
    void readHistory_missingHeader_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/{sessionId}/voice-range-history", "s-guard"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET history: path/header sessionId 불일치 → 401")
    void readHistory_mismatchedHeader_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/{sessionId}/voice-range-history", "s-path")
                .header("X-Session-Id", "s-other"))
                .andExpect(status().isUnauthorized());
    }
}
