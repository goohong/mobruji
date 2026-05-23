package com.mobruji.recommendation.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
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

import com.mobruji.recommendation.application.RecommendationService;
import com.mobruji.recommendation.application.RecommendationService.RecommendationHistorySnapshot;
import com.mobruji.recommendation.domain.RecommendationRequestEntity;
import com.mobruji.recommendation.domain.RecommendationResult;
import com.mobruji.recommendation.domain.ScoredRecommendation;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.Mood;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.user.application.SessionAuthGuard;

/**
 * MockMvc 슬라이스 가드: {@link RecommendationHistoryController}.
 *
 * <p>spec: docs/features/recommendation-history-and-feedback.md §5-2 — 컨트롤러 wiring(경로/메서드/상태코드)과
 * {@link SessionAuthGuard} 헤더 검증이 그대로 통과되는지 확인. 서비스/DTO 매핑은 별 단위 테스트로 이미 커버되어
 * 본 슬라이스에서는 (a) 200 빈/비어있지 않은 응답 형태 (b) 401 가드 분기 두 가지만 본다.
 *
 * <p>PR 3 (#924) 부터 {@link SessionAuthGuard} 는 AnonymousSessionRepository 등 의존성이 늘었기 때문에
 * 슬라이스에서 실 빈으로 띄우기 까다롭다. {@link MockitoBean} 으로 mock 화 — 401 케이스는 명시 stub.
 */
@WebMvcTest(RecommendationHistoryController.class)
@ActiveProfiles("test")
class RecommendationHistoryControllerTest {

    private static final String SESSION_ID = "session-abc";
    private static final String OTHER_SESSION_ID = "session-xyz";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RecommendationService recommendationService;

    @MockitoBean
    private SessionAuthGuard sessionAuthGuard;

    @Test
    @DisplayName("GET history: 빈 히스토리 → 200 + recommendationHistoryResponses=[]")
    void readHistory_empty_returns200WithEmptyArray() throws Exception {
        // given
        given(recommendationService.readHistoryBySessionId(SESSION_ID)).willReturn(List.of());

        // when / then
        mockMvc.perform(get("/api/v1/sessions/{sessionId}/recommendation-history", SESSION_ID)
                .header("X-Session-Id", SESSION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationHistoryResponses", hasSize(0)));
    }

    @Test
    @DisplayName("GET history: non-empty → 200 + 필드 매핑(requestId/voiceRange/mood/recommendations[].song)")
    void readHistory_nonEmpty_returns200WithMappedFields() throws Exception {
        // given
        final RecommendationRequestEntity recommendationRequestEntity = RecommendationRequestEntity.create(
                SESSION_ID, 48, 72, Mood.UPBEAT, 130, List.of());
        final Song song = Song.builder()
                .title("test-title")
                .artist("test-artist")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
        final RecommendationResult recommendationResult = new RecommendationResult(
                100L,
                List.of(new ScoredRecommendation(song, 0.8, "음역 적합", 1)));
        given(recommendationService.readHistoryBySessionId(SESSION_ID))
                .willReturn(List.of(new RecommendationHistorySnapshot(recommendationRequestEntity,
                        recommendationResult)));

        // when / then — requestId 는 영속 전이라 null. mood/voiceRange/recommendations 매핑만 확인.
        mockMvc.perform(get("/api/v1/sessions/{sessionId}/recommendation-history", SESSION_ID)
                .header("X-Session-Id", SESSION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationHistoryResponses", hasSize(1)))
                .andExpect(jsonPath("$.recommendationHistoryResponses[0].requestId", is(nullValue())))
                .andExpect(jsonPath("$.recommendationHistoryResponses[0].voiceRangeLow", is(48)))
                .andExpect(jsonPath("$.recommendationHistoryResponses[0].voiceRangeHigh", is(72)))
                .andExpect(jsonPath("$.recommendationHistoryResponses[0].mood", is("UPBEAT")))
                .andExpect(jsonPath("$.recommendationHistoryResponses[0].preferredBpm", is(130)))
                .andExpect(jsonPath("$.recommendationHistoryResponses[0].recommendations", hasSize(1)))
                .andExpect(jsonPath("$.recommendationHistoryResponses[0].recommendations[0].song.title", is(
                        "test-title")))
                .andExpect(jsonPath("$.recommendationHistoryResponses[0].recommendations[0].matchReason", is("음역 적합")))
                .andExpect(jsonPath("$.recommendationHistoryResponses[0].recommendations[0].rankPosition", is(1)));
    }

    @Test
    @DisplayName("GET history: X-Session-Id 헤더 누락 → 401 (SessionAuthGuard 차단)")
    void readHistory_missingHeader_returns401() throws Exception {
        willThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing session id"))
                .given(sessionAuthGuard).verify(SESSION_ID, null);

        mockMvc.perform(get("/api/v1/sessions/{sessionId}/recommendation-history", SESSION_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET history: path sessionId ↔ X-Session-Id 헤더 불일치 → 401")
    void readHistory_headerMismatch_returns401() throws Exception {
        willThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "session id mismatch"))
                .given(sessionAuthGuard).verify(SESSION_ID, OTHER_SESSION_ID);

        mockMvc.perform(get("/api/v1/sessions/{sessionId}/recommendation-history", SESSION_ID)
                .header("X-Session-Id", OTHER_SESSION_ID))
                .andExpect(status().isUnauthorized());
    }
}
