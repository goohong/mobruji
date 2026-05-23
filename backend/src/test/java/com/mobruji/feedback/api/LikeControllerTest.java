package com.mobruji.feedback.api;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.mobruji.auth.SessionAuthGuard;
import com.mobruji.feedback.application.LikeService;
import com.mobruji.feedback.application.LikeService.LikePageSlice;
import com.mobruji.feedback.application.ToggleResult;
import com.mobruji.feedback.domain.Like;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.Mood;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;

/**
 * {@link LikeController} MockMvc 슬라이스 가드.
 *
 * <p>spec: docs/features/recommendation-history-and-feedback.md §5-2 + §5-2-1(ADR-0011). PR #258 후속으로 적용된
 * {@link SessionAuthGuard}
 * 와이어링과 응답 직렬화(liked, songId, responses[].song 등) 회귀를 빠르게 잡는 용도.
 * 통합 테스트({@code LikeFeedbackIntegrationTest})는 full context 부팅이 필요해 회귀 감지 비용이 크다.
 *
 * <p>실제 {@code SessionAuthGuard} 를 {@link Import} 해 401 가드 동작도 함께 검증한다
 * ({@code VoiceRangeHistoryControllerTest} 동일 패턴).
 */
@WebMvcTest(LikeController.class)
@Import(SessionAuthGuard.class)
@ActiveProfiles("test")
class LikeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LikeService likeService;

    @Test
    @DisplayName("POST /api/v1/likes: body sessionId == header → 200 + liked/songId 직렬화")
    void toggle_matchingSessionId_returns200() throws Exception {
        // given
        given(likeService.toggle("s-1", 42L)).willReturn(new ToggleResult(true, 42L));

        // when / then
        mockMvc.perform(post("/api/v1/likes")
                .header("X-Session-Id", "s-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":\"s-1\",\"songId\":42}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liked", is(true)))
                .andExpect(jsonPath("$.songId", is(42)));
    }

    @Test
    @DisplayName("POST /api/v1/likes: X-Session-Id 헤더 누락 → 401 (service 미호출)")
    void toggle_missingHeader_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/likes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":\"s-1\",\"songId\":42}"))
                .andExpect(status().isUnauthorized());

        then(likeService).should(never()).toggle(anyString(), any());
    }

    @Test
    @DisplayName("POST /api/v1/likes: body/header sessionId 불일치 → 401")
    void toggle_mismatchedHeader_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/likes")
                .header("X-Session-Id", "s-other")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":\"s-1\",\"songId\":42}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/sessions/{id}/likes: 200 + responses[].song 직렬화 + 페이지 메타")
    void readBySessionId_match_returns200WithMappedResponse() throws Exception {
        // given
        final Song song = Song.builder()
                .title("벚꽃 엔딩").artist("버스커 버스커").releaseYear(2012)
                .keyOriginal(MusicalKey.A_MAJOR).bpm(132).mood(Mood.EMOTIONAL)
                .language("ko").genre("ballad")
                .metadataSource(MetadataSource.MANUAL_SEED)
                .lowMidi(57).highMidi(76)
                .build();
        // Song id 는 도메인 빌더로 직접 세팅 불가하므로 응답 직렬화는 title/artist 위주로 검증한다.
        // LikeWithSongResponse 매핑은 songsById.get(like.getSongId()) 으로 lookup — id 가 null 이어도 매핑 자체는 통과.
        final Like like = Like.create("s-2", 100L);
        given(likeService.readPageBySessionId("s-2", 0, 20))
                .willReturn(new LikePageSlice(List.of(like), Map.of(100L, song), 1L));

        // when / then
        mockMvc.perform(get("/api/v1/sessions/{sessionId}/likes", "s-2")
                .header("X-Session-Id", "s-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responses", hasSize(1)))
                .andExpect(jsonPath("$.responses[0].song.title", equalTo("벚꽃 엔딩")))
                .andExpect(jsonPath("$.responses[0].song.artist", equalTo("버스커 버스커")))
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.size", is(20)))
                .andExpect(jsonPath("$.totalCount", is(1)))
                .andExpect(jsonPath("$.hasNext", is(false)));
    }

    @Test
    @DisplayName("GET /api/v1/sessions/{id}/likes: path/header sessionId 불일치 → 401")
    void readBySessionId_mismatchedHeader_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/{sessionId}/likes", "s-path")
                .header("X-Session-Id", "s-other"))
                .andExpect(status().isUnauthorized());
    }
}
