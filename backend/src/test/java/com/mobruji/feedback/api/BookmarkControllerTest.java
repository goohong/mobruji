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
import com.mobruji.feedback.application.BookmarkService;
import com.mobruji.feedback.application.BookmarkService.BookmarkPageSlice;
import com.mobruji.feedback.application.ToggleResult;
import com.mobruji.feedback.domain.Bookmark;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.Mood;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;

/**
 * {@link BookmarkController} MockMvc 슬라이스 가드. {@code LikeControllerTest} 와 동일 패턴.
 *
 * <p>spec: docs/features/recommendation-history-and-feedback.md §5-2 + §5-2-1(ADR-0011).
 * PR #258 후속 {@link SessionAuthGuard} 와이어링 + 응답 직렬화(bookmarked, songId, responses[].song) 회귀 가드.
 */
@WebMvcTest(BookmarkController.class)
@Import(SessionAuthGuard.class)
@ActiveProfiles("test")
class BookmarkControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BookmarkService bookmarkService;

    @Test
    @DisplayName("POST /api/v1/bookmarks: body sessionId == header → 200 + bookmarked/songId 직렬화")
    void toggle_matchingSessionId_returns200() throws Exception {
        given(bookmarkService.toggle("s-1", 42L)).willReturn(new ToggleResult(true, 42L));

        mockMvc.perform(post("/api/v1/bookmarks")
                .header("X-Session-Id", "s-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":\"s-1\",\"songId\":42}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookmarked", is(true)))
                .andExpect(jsonPath("$.songId", is(42)));
    }

    @Test
    @DisplayName("POST /api/v1/bookmarks: X-Session-Id 헤더 누락 → 401 (service 미호출)")
    void toggle_missingHeader_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/bookmarks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":\"s-1\",\"songId\":42}"))
                .andExpect(status().isUnauthorized());

        then(bookmarkService).should(never()).toggle(anyString(), any());
    }

    @Test
    @DisplayName("POST /api/v1/bookmarks: body/header sessionId 불일치 → 401")
    void toggle_mismatchedHeader_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/bookmarks")
                .header("X-Session-Id", "s-other")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":\"s-1\",\"songId\":42}"))
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
        mockMvc.perform(get("/api/v1/sessions/{sessionId}/bookmarks", "s-path")
                .header("X-Session-Id", "s-other"))
                .andExpect(status().isUnauthorized());
    }
}
