package com.mobruji.song.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import com.mobruji.admin.AdminTokenVerifier;
import com.mobruji.song.application.SongSearchPage;
import com.mobruji.song.application.SongSearchService;
import com.mobruji.song.application.SongService;
import com.mobruji.song.application.SongStats;
import com.mobruji.song.application.SongStatsService;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.Mood;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.song.domain.SongNotFoundException;

@WebMvcTest(SongController.class)
@ActiveProfiles("test")
class SongControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SongService songService;

    @MockitoBean
    private SongSearchService songSearchService;

    @MockitoBean
    private SongStatsService songStatsService;

    // stats endpoint 인증 게이트(#224 #228). 기존 read/search 테스트는 stats 호출 X — bean 주입만 충족.
    @MockitoBean
    private AdminTokenVerifier adminTokenVerifier;

    @Test
    @DisplayName("GET /api/v1/songs/{id}: 200")
    void read_returns200() throws Exception {
        final Song song = Song.builder()
                .title("t").artist("a").releaseYear(2020)
                .keyOriginal(MusicalKey.C_MAJOR).bpm(120).mood(Mood.UPBEAT)
                .language("ko").genre("pop")
                .metadataSource(MetadataSource.MANUAL_SEED)
                .lowMidi(60).highMidi(76)
                .build();
        given(songService.readById(1L)).willReturn(song);

        mockMvc.perform(get("/api/v1/songs/{id}", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title", is("t")))
                .andExpect(jsonPath("$.artist", is("a")))
                .andExpect(jsonPath("$.difficulty", is("HARD")))
                .andExpect(jsonPath("$.lowestNoteName", is("C4")))
                .andExpect(jsonPath("$.highestNoteName", is("E5")));
    }

    @Test
    @DisplayName("GET /api/v1/songs/{id}: 없으면 404")
    void read_notFound_returns404() throws Exception {
        given(songService.readById(999L)).willThrow(new SongNotFoundException(999L));

        mockMvc.perform(get("/api/v1/songs/{id}", 999L))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/v1/songs?keyword=xxx: 200 + wrapper items")
    void search_returns200() throws Exception {
        final Song song = Song.builder()
                .title("벚꽃 엔딩").artist("버스커 버스커").releaseYear(2012)
                .keyOriginal(MusicalKey.A_MAJOR).bpm(132).mood(Mood.EMOTIONAL)
                .language("ko").genre("ballad").tjNumber("60540")
                .metadataSource(MetadataSource.MANUAL_SEED)
                .lowMidi(57).highMidi(76)
                .build();
        given(songSearchService.search(any()))
                .willReturn(new SongSearchPage(List.of(song), 0, 20, 1L, false));

        mockMvc.perform(get("/api/v1/songs").param("keyword", "벚꽃"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].title", is("벚꽃 엔딩")))
                .andExpect(jsonPath("$.totalCount", is(1)))
                .andExpect(jsonPath("$.hasNext", is(false)));
    }

    @Test
    @DisplayName("GET /api/v1/songs: keyword 명시+빈 → 200 + 빈 items (의도된 정책)")
    void search_blankKeyword_returnsEmpty() throws Exception {
        given(songSearchService.search(any()))
                .willReturn(new SongSearchPage(List.of(), 0, 20, 0L, false));

        mockMvc.perform(get("/api/v1/songs").param("keyword", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.totalCount", is(0)));
    }

    @Test
    @DisplayName("GET /api/v1/songs/stats: 유효 토큰 → 200")
    void stats_validToken_returns200() throws Exception {
        willDoNothing().given(adminTokenVerifier).verify("ok-token");
        given(songStatsService.getStats()).willReturn(new SongStats(
                42L,
                Map.of(MetadataSource.MANUAL_SEED, 42L),
                0.95,
                Instant.parse("2026-05-23T00:00:00Z"),
                Instant.parse("2026-05-23T00:30:00Z")));

        mockMvc.perform(get("/api/v1/songs/stats").header("X-Admin-Token", "ok-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(42)))
                .andExpect(jsonPath("$.avgConfidence", is(0.95)))
                .andExpect(jsonPath("$.lastBackfillAt", is("2026-05-23T00:00:00Z")))
                .andExpect(jsonPath("$.lastAlbumCoverBackfillAt", is("2026-05-23T00:30:00Z")));
    }

    @Test
    @DisplayName("GET /api/v1/songs/stats: 토큰 누락/불일치 → 401, service 미호출")
    void stats_invalidToken_returns401() throws Exception {
        willThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing admin token"))
                .given(adminTokenVerifier).verify(any());

        mockMvc.perform(get("/api/v1/songs/stats"))
                .andExpect(status().isUnauthorized());
    }
}
