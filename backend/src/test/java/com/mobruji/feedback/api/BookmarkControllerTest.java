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

import com.mobruji.feedback.application.BookmarkService;
import com.mobruji.feedback.application.BookmarkService.BookmarkPageSlice;
import com.mobruji.feedback.application.ToggleResult;
import com.mobruji.feedback.domain.Bookmark;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.Mood;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.user.application.SessionAuthGuard;

/**
 * {@link BookmarkController} MockMvc 슬라이스 가드. {@code LikeControllerTest} 와 동일 패턴.
 *
 * <p>spec: docs/features/recommendation-history-and-feedback.md §5-2 + §5-2-1(ADR-0011).
 * PR #258 후속 {@link SessionAuthGuard} 와이어링 + 응답 직렬화(bookmarked, songId, responses[].song) 회귀 가드.
 *
 * <p>PR 3 (#924) 부터 {@link SessionAuthGuard} 는 의존성이 늘어 슬라이스에서 mock 화. 401 케이스는 명시 stub.
 */
@WebMvcTest(BookmarkController.class)
@ActiveProfiles("test")
class BookmarkControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BookmarkService bookmarkService;

    @MockitoBean
    private SessionAuthGuard sessionAuthGuard;

    // UUIDv4 (#948 SessionIdPatterns 강제) — POST body sessionId 는 @Pattern 검증을 통과해야 SessionAuthGuard 로 진입
    private static final String SESSION_ID = "550e8400-e29b-41d4-a716-11ee5e55c201";
    private static final String SESSION_ID_OTHER = "550e8400-e29b-41d4-a716-11ee5e55c202";

    @Test
    @DisplayName("POST /api/v1/bookmarks: body sessionId == header → 200 + bookmarked/songId 직렬화")
    void toggle_matchingSessionId_returns200() throws Exception {
        given(bookmarkService.toggle(SESSION_ID, 42L)).willReturn(new ToggleResult(true, 42L));

        mockMvc.perform(post("/api/v1/bookmarks")
                .header("X-Session-Id", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":\"" + SESSION_ID + "\",\"songId\":42}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookmarked", is(true)))
                .andExpect(jsonPath("$.songId", is(42)));
    }

    @Test
    @DisplayName("POST /api/v1/bookmarks: X-Session-Id 헤더 누락 → 401 (service 미호출)")
    void toggle_missingHeader_returns401() throws Exception {
        willThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing session id"))
                .given(sessionAuthGuard).verify(SESSION_ID, null);

        mockMvc.perform(post("/api/v1/bookmarks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":\"" + SESSION_ID + "\",\"songId\":42}"))
                .andExpect(status().isUnauthorized());

        then(bookmarkService).should(never()).toggle(anyString(), any());
    }

    @Test
    @DisplayName("POST /api/v1/bookmarks: body/header sessionId 불일치 → 401")
    void toggle_mismatchedHeader_returns401() throws Exception {
        willThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "session id mismatch"))
                .given(sessionAuthGuard).verify(SESSION_ID, SESSION_ID_OTHER);

        mockMvc.perform(post("/api/v1/bookmarks")
                .header("X-Session-Id", SESSION_ID_OTHER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":\"" + SESSION_ID + "\",\"songId\":42}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/sessions/{id}/bookmarks: 200 + responses[].song 직렬화 + 페이지 메타")
    void readBySessionId_match_returns200WithMappedResponse() throws Exception {
        final Song song = Song.builder()
                .title("좋은 날").artist("아이유").releaseYear(2010)
                .keyOriginal(MusicalKey.C_MAJOR).bpm(124).mood(Mood.UPBEAT)
                .language("ko").genre("pop")
                .metadataSource(MetadataSource.MANUAL_SEED)
                .lowMidi(60).highMidi(81)
                .build();
        final Bookmark bookmark = Bookmark.create("s-2", 100L);
        given(bookmarkService.readPageBySessionId("s-2", 0, 20))
                .willReturn(new BookmarkPageSlice(List.of(bookmark), Map.of(100L, song), 1L));

        mockMvc.perform(get("/api/v1/sessions/{sessionId}/bookmarks", "s-2")
                .header("X-Session-Id", "s-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responses", hasSize(1)))
                .andExpect(jsonPath("$.responses[0].song.title", equalTo("좋은 날")))
                .andExpect(jsonPath("$.responses[0].song.artist", equalTo("아이유")))
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.size", is(20)))
                .andExpect(jsonPath("$.totalCount", is(1)))
                .andExpect(jsonPath("$.hasNext", is(false)));
    }

    @Test
    @DisplayName("GET /api/v1/sessions/{id}/bookmarks: path/header sessionId 불일치 → 401")
    void readBySessionId_mismatchedHeader_returns401() throws Exception {
        willThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "session id mismatch"))
                .given(sessionAuthGuard).verify("s-path", "s-other");

        mockMvc.perform(get("/api/v1/sessions/{sessionId}/bookmarks", "s-path")
                .header("X-Session-Id", "s-other"))
                .andExpect(status().isUnauthorized());
    }
}
