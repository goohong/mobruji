package com.mobruji.song.api;

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
import com.mobruji.song.application.SongVocalRangeEstimateCommand;
import com.mobruji.song.application.SongVocalRangeEstimateCommand.EstimateSummary;

/**
 * {@link EstimateVocalRangeController} 슬라이스 테스트 — admin 게이트(401) + 트리거 응답 shape + limit 매핑 (#1788).
 */
@WebMvcTest(EstimateVocalRangeController.class)
@ActiveProfiles("test")
class EstimateVocalRangeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminTokenVerifier adminTokenVerifier;

    @MockitoBean
    private SongVocalRangeEstimateCommand estimateCommand;

    @Test
    @DisplayName("POST estimate-vocal-range: 정상 토큰 + limit → 200 + 추정 집계")
    void trigger_returns200() throws Exception {
        willDoNothing().given(adminTokenVerifier).verify("valid-token");
        given(estimateCommand.runEstimate(eq(OptionalInt.of(5))))
                .willReturn(new EstimateSummary(5, 4, 1, 0));

        mockMvc.perform(post("/api/v1/admin/songs/estimate-vocal-range")
                .header("X-Admin-Token", "valid-token")
                .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanned").value(5))
                .andExpect(jsonPath("$.applied").value(4))
                .andExpect(jsonPath("$.skippedUnestimable").value(1))
                .andExpect(jsonPath("$.skippedNotApplied").value(0));
    }

    @Test
    @DisplayName("POST estimate-vocal-range: limit 미지정 → 전체 미보유 곡 처리 (OptionalInt.empty)")
    void trigger_withoutLimit_processesAll() throws Exception {
        willDoNothing().given(adminTokenVerifier).verify("valid-token");
        given(estimateCommand.runEstimate(eq(OptionalInt.empty())))
                .willReturn(new EstimateSummary(20, 18, 2, 0));

        mockMvc.perform(post("/api/v1/admin/songs/estimate-vocal-range")
                .header("X-Admin-Token", "valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanned").value(20))
                .andExpect(jsonPath("$.applied").value(18));
    }

    @Test
    @DisplayName("POST estimate-vocal-range: 토큰 헤더 누락 → 401")
    void trigger_missingToken_returns401() throws Exception {
        willThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing admin token"))
                .given(adminTokenVerifier).verify(null);

        mockMvc.perform(post("/api/v1/admin/songs/estimate-vocal-range"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST estimate-vocal-range: 토큰 불일치 → 401")
    void trigger_invalidToken_returns401() throws Exception {
        willThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid admin token"))
                .given(adminTokenVerifier).verify("wrong-token");

        mockMvc.perform(post("/api/v1/admin/songs/estimate-vocal-range")
                .header("X-Admin-Token", "wrong-token"))
                .andExpect(status().isUnauthorized());
    }
}
