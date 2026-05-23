package com.mobruji.voice.api;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import com.mobruji.user.application.SessionAuthGuard;
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
 * <p>{@code SessionAuthGuard} 는 PR 3 (#924) 부터 AnonymousSessionRepository 등 의존성이 늘었기 때문에
 * 슬라이스에서 실 빈으로 띄우기 까다롭다. {@link MockitoBean} 으로 mock 화하고 401 케이스는 명시적으로
 * {@code willThrow} 한다. 가드 본체 동작 검증은 {@link com.mobruji.user.application.SessionAuthGuardTest} 가 담당.
 */
@WebMvcTest(VoiceRangeHistoryController.class)
@ActiveProfiles("test")
class VoiceRangeHistoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VoiceRangeService voiceRangeService;

    @MockitoBean
    private SessionAuthGuard sessionAuthGuard;

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
    @DisplayName("GET history: X-Session-Id 헤더 누락 → 401 (가드가 throw 하도록 stub)")
    void readHistory_missingHeader_returns401() throws Exception {
        willThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing session id"))
                .given(sessionAuthGuard).verify("s-guard", null);

        mockMvc.perform(get("/api/v1/sessions/{sessionId}/voice-range-history", "s-guard"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET history: path/header sessionId 불일치 → 401 (가드가 throw 하도록 stub)")
    void readHistory_mismatchedHeader_returns401() throws Exception {
        willThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "session id mismatch"))
                .given(sessionAuthGuard).verify("s-path", "s-other");

        mockMvc.perform(get("/api/v1/sessions/{sessionId}/voice-range-history", "s-path")
                .header("X-Session-Id", "s-other"))
                .andExpect(status().isUnauthorized());
    }
}
