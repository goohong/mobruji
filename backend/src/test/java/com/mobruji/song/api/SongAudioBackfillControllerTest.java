package com.mobruji.song.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.OptionalInt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import com.mobruji.admin.AdminTokenVerifier;
import com.mobruji.song.application.AudioBackfillOnDemandService;
import com.mobruji.song.application.AudioBackfillOnDemandService.TriggerResult;

/**
 * {@link SongAudioBackfillController} 슬라이스 테스트 — admin 게이트(401) + 트리거 응답 shape + 파라미터 매핑.
 */
@WebMvcTest(SongAudioBackfillController.class)
@ActiveProfiles("test")
class SongAudioBackfillControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminTokenVerifier adminTokenVerifier;

    @MockitoBean
    private AudioBackfillOnDemandService backfillService;

    @Test
    @DisplayName("POST audio-backfill: 정상 토큰 + dryRun=false → 200 + 트리거 집계")
    void trigger_returns200() throws Exception {
        willDoNothing().given(adminTokenVerifier).verify("valid-token");
        given(backfillService.trigger(
                eq(AudioBackfillOnDemandService.TARGET_MISSING_RANGE), eq(OptionalInt.of(5)), eq(false)))
                .willReturn(new TriggerResult(12, 5, false, true));

        mockMvc.perform(post("/api/v1/admin/songs/audio-backfill")
                .header("X-Admin-Token", "valid-token")
                .param("limit", "5")
                .param("dryRun", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates").value(12))
                .andExpect(jsonPath("$.selected").value(5))
                .andExpect(jsonPath("$.dryRun").value(false))
                .andExpect(jsonPath("$.started").value(true));
    }

    @Test
    @DisplayName("POST audio-backfill: dryRun 기본값 true → 분석 없이 집계만")
    void trigger_dryRunDefaultsTrue() throws Exception {
        willDoNothing().given(adminTokenVerifier).verify("valid-token");
        given(backfillService.trigger(any(), any(), eq(true)))
                .willReturn(new TriggerResult(7, 7, true, false));

        mockMvc.perform(post("/api/v1/admin/songs/audio-backfill")
                .header("X-Admin-Token", "valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.started").value(false));
    }

    @Test
    @DisplayName("POST audio-backfill: 토큰 헤더 누락 → 401")
    void trigger_missingToken_returns401() throws Exception {
        willThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing admin token"))
                .given(adminTokenVerifier).verify(null);

        mockMvc.perform(post("/api/v1/admin/songs/audio-backfill"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST audio-backfill: 토큰 불일치 → 401")
    void trigger_invalidToken_returns401() throws Exception {
        willThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid admin token"))
                .given(adminTokenVerifier).verify("wrong-token");

        mockMvc.perform(post("/api/v1/admin/songs/audio-backfill")
                .header("X-Admin-Token", "wrong-token"))
                .andExpect(status().isUnauthorized());
    }
}
