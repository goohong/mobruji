package com.mobruji.voice;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobruji.voice.dto.VoiceRangeCreateRequest;
import com.mobruji.voice.dto.VoiceRangeResponse;
import com.mobruji.voice.dto.VoiceRangeUpdateRequest;

@WebMvcTest(VoiceRangeController.class)
@ActiveProfiles("test")
class VoiceRangeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private VoiceRangeService voiceRangeService;

    @Test
    @DisplayName("POST /api/v1/voice-ranges: 201 + 응답 바디")
    void create_returns201() throws Exception {
        // given
        final VoiceRangeCreateRequest request = new VoiceRangeCreateRequest(
                "s", 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK);
        final LocalDateTime now = LocalDateTime.now();
        final VoiceRangeResponse response = new VoiceRangeResponse(
                1L, "s", 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK, now, now);
        given(voiceRangeService.createOrReplace(any(VoiceRangeCreateRequest.class))).willReturn(response);

        // when / then
        mockMvc.perform(post("/api/v1/voice-ranges")
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
                .contentType(MediaType.APPLICATION_JSON)
                .content(bad))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/voice-ranges/{sessionId}: 200 + 응답 바디")
    void read_returns200() throws Exception {
        // given
        final LocalDateTime now = LocalDateTime.now();
        final VoiceRangeResponse response = new VoiceRangeResponse(
                1L, "s", 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK, now, now);
        given(voiceRangeService.readBySessionId("s")).willReturn(response);

        // when / then
        mockMvc.perform(get("/api/v1/voice-ranges/{sessionId}", "s"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId", is("s")));
    }

    @Test
    @DisplayName("GET 없는 sessionId: 404")
    void read_notFound_returns404() throws Exception {
        given(voiceRangeService.readBySessionId("missing"))
                .willThrow(new VoiceRangeNotFoundException("missing"));

        mockMvc.perform(get("/api/v1/voice-ranges/{sessionId}", "missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT /api/v1/voice-ranges/{sessionId}: 200 + 업데이트된 응답")
    void update_returns200() throws Exception {
        // given
        final VoiceRangeUpdateRequest request = new VoiceRangeUpdateRequest(
                50, 72, VoiceRangeSourceMethod.MIC_MEASURE);
        final LocalDateTime now = LocalDateTime.now();
        final VoiceRangeResponse response = new VoiceRangeResponse(
                1L, "s", 50, 72, VoiceRangeSourceMethod.MIC_MEASURE, now, now);
        given(voiceRangeService.updateBySessionId(eq("s"), any(VoiceRangeUpdateRequest.class)))
                .willReturn(response);

        // when / then
        mockMvc.perform(put("/api/v1/voice-ranges/{sessionId}", "s")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lowestNoteMidi", is(50)))
                .andExpect(jsonPath("$.sourceMethod", is("MIC_MEASURE")));
    }
}
