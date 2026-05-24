package com.mobruji.feedback.api;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import com.mobruji.feedback.application.LikeService;
import com.mobruji.feedback.application.LikeService.LikePageSlice;
import com.mobruji.feedback.application.ToggleResult;
import com.mobruji.feedback.domain.Like;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.Mood;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.user.application.SessionAuthGuard;

/**
 * {@link LikeController} MockMvc 슬라이스 가드.
 *
 * <p>spec: docs/features/recommendation-history-and-feedback.md §5-2 + §5-2-1(ADR-0011). PR #258 후속으로 적용된
 * {@link SessionAuthGuard} 와이어링과 응답 직렬화(liked, songId, responses[].song 등) 회귀를 빠르게 잡는 용도.
 * 통합 테스트({@code LikeFeedbackIntegrationTest})는 full context 부팅이 필요해 회귀 감지 비용이 크다.
 *
 * <p>PR 3 (#924) 부터 {@link SessionAuthGuard} 는 AnonymousSessionRepository 등 의존성이 늘었기 때문에
 * 슬라이스 컨텍스트에서 실 빈으로 띄우기 까다롭다. {@link MockitoBean} 으로 mock 화 — verify() 는 default
 * no-op 라 success 시나리오에 영향 없음. 401 케이스는 명시적으로 {@code willThrow} 한다.
 */
@WebMvcTest(LikeController.class)
@ActiveProfiles("test")
class LikeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LikeService likeService;

    @MockitoBean
    private SessionAuthGuard sessionAuthGuard;

    // UUIDv4 (#948 SessionIdPatterns 강제) — POST body sessionId 는 @Pattern 검증을 통과해야 SessionAuthGuard 로 들어감
    private static final String SESSION_ID = "550e8400-e29b-41d4-a716-11ee5e55c101";
    private static final String SESSION_ID_OTHER = "550e8400-e29b-41d4-a716-11ee5e55c102";

    @Test
    @DisplayName("POST /api/v1/likes: body sessionId == header → 200 + liked/songId 직렬화")
    void toggle_matchingSessionId_returns200() throws Exception {
        // given
        given(likeService.toggle(SESSION_ID, 42L)).willReturn(new ToggleResult(true, 42L));

        // when / then
        mockMvc.perform(post("/api/v1/likes")
                .header("X-Session-Id", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":\"" + SESSION_ID + "\",\"songId\":42}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liked", is(true)))
                .andExpect(jsonPath("$.songId", is(42)));
    }

    @Test
    @DisplayName("POST /api/v1/likes: X-Session-Id 헤더 누락 → 401 (service 미호출)")
    void toggle_missingHeader_returns401() throws Exception {
        willThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing session id"))
                .given(sessionAuthGuard).verify(SESSION_ID, null);

        mockMvc.perform(post("/api/v1/likes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":\"" + SESSION_ID + "\",\"songId\":42}"))
                .andExpect(status().isUnauthorized());

        then(likeService).should(never()).toggle(anyString(), any());
    }

    @Test
    @DisplayName("POST /api/v1/likes: body/header sessionId 불일치 → 401")
    void toggle_mismatchedHeader_returns401() throws Exception {
        willThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "session id mismatch"))
                .given(sessionAuthGuard).verify(SESSION_ID, SESSION_ID_OTHER);

        mockMvc.perform(post("/api/v1/likes")
                .header("X-Session-Id", SESSION_ID_OTHER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":\"" + SESSION_ID + "\",\"songId\":42}"))
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
        willThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "session id mismatch"))
                .given(sessionAuthGuard).verify("s-path", "s-other");

        mockMvc.perform(get("/api/v1/sessions/{sessionId}/likes", "s-path")
                .header("X-Session-Id", "s-other"))
                .andExpect(status().isUnauthorized());
    }
}
