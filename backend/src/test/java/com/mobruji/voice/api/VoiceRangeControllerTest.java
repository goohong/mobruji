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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobruji.user.application.SessionAuthGuard;
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
 * <p>PR 3 (#924) 부터 {@link SessionAuthGuard} 는 AnonymousSessionRepository 등 의존성이 늘었기 때문에
 * 슬라이스 컨텍스트에서 실 빈으로 띄우기 까다롭다. {@link MockitoBean} 으로 mock 화 — verify() 는 default
 * no-op 라 success 시나리오에 영향 없음. 401 케이스는 별 슬라이스/통합 테스트와 가드 단위 테스트에서 담당.
 *
 * <p>#948: VoiceRangeCreateRequest.sessionId 가 UUIDv4 @Pattern 강제이므로 fixture 는 UUIDv4 사용.
 */
@WebMvcTest(VoiceRangeController.class)
@ActiveProfiles("test")
class VoiceRangeControllerTest {

    private static final String SESSION_ID = "550e8400-e29b-41d4-a716-446655449201";
    private static final String SESSION_ID_MISSING = "550e8400-e29b-41d4-a716-446655449202";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private VoiceRangeService voiceRangeService;

    @MockitoBean
    private SessionAuthGuard sessionAuthGuard;

    @Test
    @DisplayName("POST /api/v1/voice-ranges: 201 + 응답 바디")
    void create_returns201() throws Exception {
        // given
        final VoiceRangeCreateRequest request = new VoiceRangeCreateRequest(
                SESSION_ID, 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK);
        final VoiceRange voiceRange = VoiceRange.create(SESSION_ID, 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK);
        given(voiceRangeService.createOrReplace(any(CreateVoiceRangeCommand.class))).willReturn(voiceRange);

        // when / then
        mockMvc.perform(post("/api/v1/voice-ranges")
                .header("X-Session-Id", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sessionId", is(SESSION_ID)))
                .andExpect(jsonPath("$.lowestNoteMidi", is(48)));
    }

    @Test
    @DisplayName("POST 검증 실패(저음 범위 밖): 400")
    void create_invalidInput_returns400() throws Exception {
        final String bad = """
                {"sessionId":"%s","lowestNoteMidi":5,"highestNoteMidi":69,"sourceMethod":"OCTAVE_PICK"}
                """.formatted(SESSION_ID);
        mockMvc.perform(post("/api/v1/voice-ranges")
                .header("X-Session-Id", SESSION_ID)
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
                .header("X-Session-Id", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(bad))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST 검증 실패(sessionId 비-UUIDv4 형식, #948): 400")
    void create_nonUuidSessionId_returns400() throws Exception {
        final String bad = """
                {"sessionId":"k6-load-1716543210-3","lowestNoteMidi":48,"highestNoteMidi":69,"sourceMethod":"OCTAVE_PICK"}
                """;
        mockMvc.perform(post("/api/v1/voice-ranges")
                .header("X-Session-Id", "k6-load-1716543210-3")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bad))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST 검증 실패(sourceMethod null): 400")
    void create_nullSourceMethod_returns400() throws Exception {
        final String bad = """
                {"sessionId":"%s","lowestNoteMidi":48,"highestNoteMidi":69,"sourceMethod":null}
                """.formatted(SESSION_ID);
        mockMvc.perform(post("/api/v1/voice-ranges")
                .header("X-Session-Id", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(bad))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/voice-ranges/{sessionId}: 200 + 응답 바디")
    void read_returns200() throws Exception {
        // given
        final VoiceRange voiceRange = VoiceRange.create(SESSION_ID, 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK);
        given(voiceRangeService.readBySessionId(SESSION_ID)).willReturn(voiceRange);

        // when / then
        mockMvc.perform(get("/api/v1/voice-ranges/{sessionId}", SESSION_ID)
                .header("X-Session-Id", SESSION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId", is(SESSION_ID)));
    }

    @Test
    @DisplayName("GET 없는 sessionId: 404")
    void read_notFound_returns404() throws Exception {
        given(voiceRangeService.readBySessionId(SESSION_ID_MISSING))
                .willThrow(new VoiceRangeNotFoundException(SESSION_ID_MISSING));

        mockMvc.perform(get("/api/v1/voice-ranges/{sessionId}", SESSION_ID_MISSING)
                .header("X-Session-Id", SESSION_ID_MISSING))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT /api/v1/voice-ranges/{sessionId}: 200 + 업데이트된 응답")
    void update_returns200() throws Exception {
        // given
        final VoiceRangeUpdateRequest request = new VoiceRangeUpdateRequest(
                50, 72, VoiceRangeSourceMethod.MIC_MEASURE);
        final VoiceRange voiceRange = VoiceRange.create(SESSION_ID, 50, 72, VoiceRangeSourceMethod.MIC_MEASURE);
        given(voiceRangeService.updateBySessionId(eq(SESSION_ID), any(UpdateVoiceRangeCommand.class)))
                .willReturn(voiceRange);

        // when / then
        mockMvc.perform(put("/api/v1/voice-ranges/{sessionId}", SESSION_ID)
                .header("X-Session-Id", SESSION_ID)
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
        mockMvc.perform(put("/api/v1/voice-ranges/{sessionId}", SESSION_ID)
                .header("X-Session-Id", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(bad))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT 없는 sessionId: 404")
    void update_notFound_returns404() throws Exception {
        final VoiceRangeUpdateRequest request = new VoiceRangeUpdateRequest(
                50, 72, VoiceRangeSourceMethod.MIC_MEASURE);
        given(voiceRangeService.updateBySessionId(eq(SESSION_ID_MISSING), any(UpdateVoiceRangeCommand.class)))
                .willThrow(new VoiceRangeNotFoundException(SESSION_ID_MISSING));

        mockMvc.perform(put("/api/v1/voice-ranges/{sessionId}", SESSION_ID_MISSING)
                .header("X-Session-Id", SESSION_ID_MISSING)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }
}
