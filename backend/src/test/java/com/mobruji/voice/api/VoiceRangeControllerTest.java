package com.mobruji.voice.api;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobruji.auth.SessionAuthGuard;
import com.mobruji.voice.api.dto.VoiceRangeCreateRequest;
import com.mobruji.voice.api.dto.VoiceRangeUpdateRequest;
import com.mobruji.voice.application.CreateVoiceRangeCommand;
import com.mobruji.voice.application.UpdateVoiceRangeCommand;
import com.mobruji.voice.application.VoiceRangeService;
import com.mobruji.voice.domain.VoiceRange;
import com.mobruji.voice.domain.VoiceRangeNotFoundException;
import com.mobruji.voice.domain.VoiceRangeSourceMethod;

/**
 * {@link VoiceRangeController} MockMvc 슬라이스 가드.
 *
 * <p>실제 {@link SessionAuthGuard} 를 {@link Import} 해 ADR-0011 §28 / 이슈 #868 후속 적용된
 * 인증 게이트 동작도 함께 검증한다 ({@code LikeControllerTest} 동일 패턴). POST 는 body sessionId,
 * GET/PUT 은 path sessionId 가 {@code X-Session-Id} 헤더와 일치해야 한다.
 */
@WebMvcTest(VoiceRangeController.class)
@Import(SessionAuthGuard.class)
@ActiveProfiles("test")
class VoiceRangeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private VoiceRangeService voiceRangeService;

    @Test
    @DisplayName("POST /api/v1/voice-ranges: 201 + 응답 바디")
    void create_returns201() throws Exception {
        // given
        final VoiceRangeCreateRequest request = new VoiceRangeCreateRequest(
                "s", 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK);
        final VoiceRange voiceRange = VoiceRange.create("s", 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK);
        given(voiceRangeService.createOrReplace(any(CreateVoiceRangeCommand.class))).willReturn(voiceRange);

        // when / then
        mockMvc.perform(post("/api/v1/voice-ranges")
                .header("X-Session-Id", "s")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sessionId", is("s")))
                .andExpect(jsonPath("$.lowestNoteMidi", is(48)));
    }

    @Test
    @DisplayName("POST 검증 실패(저음 범위 밖): 400")
    void create_invalidInput_returns400() throws Exception {
        final String bad = """
                {"sessionId":"s","lowestNoteMidi":5,"highestNoteMidi":69,"sourceMethod":"OCTAVE_PICK"}
                """;
        mockMvc.perform(post("/api/v1/voice-ranges")
                .header("X-Session-Id", "s")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bad))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST 검증 실패(sessionId blank): 400")
    void create_blankSessionId_returns400() throws Exception {
        final String bad = """
                {"sessionId":"","lowestNoteMidi":48,"highestNoteMidi":69,"sourceMethod":"OCTAVE_PICK"}
                """;
        mockMvc.perform(post("/api/v1/voice-ranges")
                .header("X-Session-Id", "s")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bad))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST 검증 실패(sourceMethod null): 400")
    void create_nullSourceMethod_returns400() throws Exception {
        final String bad = """
                {"sessionId":"s","lowestNoteMidi":48,"highestNoteMidi":69,"sourceMethod":null}
                """;
        mockMvc.perform(post("/api/v1/voice-ranges")
                .header("X-Session-Id", "s")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bad))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/voice-ranges/{sessionId}: 200 + 응답 바디")
    void read_returns200() throws Exception {
        // given
        final VoiceRange voiceRange = VoiceRange.create("s", 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK);
        given(voiceRangeService.readBySessionId("s")).willReturn(voiceRange);

        // when / then
        mockMvc.perform(get("/api/v1/voice-ranges/{sessionId}", "s")
                .header("X-Session-Id", "s"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId", is("s")));
    }

    @Test
    @DisplayName("GET 없는 sessionId: 404")
    void read_notFound_returns404() throws Exception {
        given(voiceRangeService.readBySessionId("missing"))
                .willThrow(new VoiceRangeNotFoundException("missing"));

        mockMvc.perform(get("/api/v1/voice-ranges/{sessionId}", "missing")
                .header("X-Session-Id", "missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT /api/v1/voice-ranges/{sessionId}: 200 + 업데이트된 응답")
    void update_returns200() throws Exception {
        // given
        final VoiceRangeUpdateRequest request = new VoiceRangeUpdateRequest(
                50, 72, VoiceRangeSourceMethod.MIC_MEASURE);
        final VoiceRange voiceRange = VoiceRange.create("s", 50, 72, VoiceRangeSourceMethod.MIC_MEASURE);
        given(voiceRangeService.updateBySessionId(eq("s"), any(UpdateVoiceRangeCommand.class)))
                .willReturn(voiceRange);

        // when / then
        mockMvc.perform(put("/api/v1/voice-ranges/{sessionId}", "s")
                .header("X-Session-Id", "s")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lowestNoteMidi", is(50)))
                .andExpect(jsonPath("$.sourceMethod", is("MIC_MEASURE")));
    }

    @Test
    @DisplayName("PUT 검증 실패(lowestNoteMidi null): 400")
    void update_invalidInput_returns400() throws Exception {
        final String bad = """
                {"lowestNoteMidi":null,"highestNoteMidi":72,"sourceMethod":"MIC_MEASURE"}
                """;
        mockMvc.perform(put("/api/v1/voice-ranges/{sessionId}", "s")
                .header("X-Session-Id", "s")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bad))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT 없는 sessionId: 404")
    void update_notFound_returns404() throws Exception {
        final VoiceRangeUpdateRequest request = new VoiceRangeUpdateRequest(
                50, 72, VoiceRangeSourceMethod.MIC_MEASURE);
        given(voiceRangeService.updateBySessionId(eq("missing"), any(UpdateVoiceRangeCommand.class)))
                .willThrow(new VoiceRangeNotFoundException("missing"));

        mockMvc.perform(put("/api/v1/voice-ranges/{sessionId}", "missing")
                .header("X-Session-Id", "missing")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }
}
