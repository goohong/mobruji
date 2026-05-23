package com.mobruji.recommendation.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.mobruji.recommendation.application.CreateRecommendationCommand;
import com.mobruji.recommendation.application.RecommendationService;
import com.mobruji.recommendation.domain.RecommendationNotFoundException;
import com.mobruji.recommendation.domain.RecommendationResult;
import com.mobruji.recommendation.domain.ScoredRecommendation;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;

/**
 * MockMvc 슬라이스 가드: {@link RecommendationController}.
 *
 * <p>spec: {@code docs/features/recommendation-algorithm-v1.md} §3 — 컨트롤러 wiring(경로/메서드/
 * 상태코드)과 {@link com.mobruji.recommendation.api.dto.RecommendationCreateRequest}
 * Bean Validation 매핑(400), {@link RecommendationNotFoundException} → 404 매핑이 그대로
 * 통과되는지 확인. 서비스/스코어링/DTO 매핑은 단위 테스트로 이미 커버되어 본 슬라이스에서는
 * (a) POST 201 (b) POST 400 검증 (c) GET 200 (d) GET 404 만 본다.
 *
 * <p>HistoryController 와 달리 인증 가드가 없는 endpoint 라 {@code @Import} 없이 컨트롤러만
 * 슬라이스한다. spec 상 sessionId 는 body 내부 필드로 받으며 path 인증은 적용 대상 아님.
 */
@WebMvcTest(RecommendationController.class)
@ActiveProfiles("test")
class RecommendationControllerTest {

    private static final String SESSION_ID = "session-abc";
    private static final long REQUEST_ID = 100L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RecommendationService recommendationService;

    @Test
    @DisplayName("POST /recommendations: 정상 입력 → 201 + requestId/recommendations 매핑")
    void create_validRequest_returns201WithMappedBody() throws Exception {
        // given
        final Song song = Song.builder()
                .title("test-title")
                .artist("test-artist")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
        final RecommendationResult recommendationResult = new RecommendationResult(
                REQUEST_ID,
                List.of(new ScoredRecommendation(song, 0.9, "음역 적합", 1)));
        given(recommendationService.create(any(CreateRecommendationCommand.class)))
                .willReturn(recommendationResult);

        // when / then
        mockMvc.perform(post("/api/v1/recommendations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "sessionId": "%s",
                          "voiceRangeLow": 48,
                          "voiceRangeHigh": 72,
                          "mood": "UPBEAT",
                          "preferredBpm": 130
                        }
                        """.formatted(SESSION_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestId", is((int) REQUEST_ID)))
                .andExpect(jsonPath("$.recommendations", hasSize(1)))
                .andExpect(jsonPath("$.recommendations[0].song.title", is("test-title")))
                .andExpect(jsonPath("$.recommendations[0].matchReason", is("음역 적합")))
                .andExpect(jsonPath("$.recommendations[0].rankPosition", is(1)));
    }

    @Test
    @DisplayName("POST /recommendations: sessionId blank → 400 (@NotBlank)")
    void create_blankSessionId_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/recommendations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "sessionId": "",
                          "voiceRangeLow": 48,
                          "voiceRangeHigh": 72
                        }
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /recommendations: voiceRangeLow null → 400 (@NotNull)")
    void create_nullVoiceRangeLow_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/recommendations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "sessionId": "%s",
                          "voiceRangeHigh": 72
                        }
                        """.formatted(SESSION_ID)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /recommendations: voiceRangeHigh > 119 → 400 (@Max)")
    void create_voiceRangeHighOutOfRange_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/recommendations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "sessionId": "%s",
                          "voiceRangeLow": 48,
                          "voiceRangeHigh": 200
                        }
                        """.formatted(SESSION_ID)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /recommendations/{id}: 존재 → 200 + body 매핑")
    void read_existing_returns200() throws Exception {
        // given
        final Song song = Song.builder()
                .title("read-title")
                .artist("read-artist")
                .keyOriginal(MusicalKey.D_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
        final RecommendationResult recommendationResult = new RecommendationResult(
                REQUEST_ID,
                List.of(new ScoredRecommendation(song, 0.7, "분위기 적합", 1)));
        given(recommendationService.readById(REQUEST_ID)).willReturn(recommendationResult);

        // when / then
        mockMvc.perform(get("/api/v1/recommendations/{id}", REQUEST_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId", is((int) REQUEST_ID)))
                .andExpect(jsonPath("$.recommendations", hasSize(1)))
                .andExpect(jsonPath("$.recommendations[0].song.title", is("read-title")))
                .andExpect(jsonPath("$.recommendations[0].matchReason", is("분위기 적합")));
    }

    @Test
    @DisplayName("GET /recommendations/{id}: 부재 → 404 (RecommendationNotFoundException @ResponseStatus)")
    void read_missing_returns404() throws Exception {
        // given
        willThrow(new RecommendationNotFoundException(REQUEST_ID))
                .given(recommendationService).readById(REQUEST_ID);

        // when / then
        mockMvc.perform(get("/api/v1/recommendations/{id}", REQUEST_ID))
                .andExpect(status().isNotFound());
    }
}
