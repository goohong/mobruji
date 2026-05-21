package com.mobruji.song.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.mobruji.song.application.SongService;
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

    @MockBean
    private SongService songService;

    @MockBean
    private SongStatsService songStatsService;

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
    @DisplayName("GET /api/v1/songs?keyword=xxx: 200 + 리스트")
    void search_returns200() throws Exception {
        final Song song = Song.builder()
                .title("벚꽃 엔딩").artist("버스커 버스커").releaseYear(2012)
                .keyOriginal(MusicalKey.A_MAJOR).bpm(132).mood(Mood.EMOTIONAL)
                .language("ko").genre("ballad").tjNumber("60540")
                .metadataSource(MetadataSource.MANUAL_SEED)
                .lowMidi(57).highMidi(76)
                .build();
        given(songService.searchByKeyword("벚꽃")).willReturn(List.of(song));

        mockMvc.perform(get("/api/v1/songs").param("keyword", "벚꽃"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].title", is("벚꽃 엔딩")));
    }
}
