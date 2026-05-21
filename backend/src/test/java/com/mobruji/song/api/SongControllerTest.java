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

import com.mobruji.song.api.dto.SongResponse;

import com.mobruji.song.application.SongService;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.Mood;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.SongNotFoundException;

@WebMvcTest(SongController.class)
@ActiveProfiles("test")
class SongControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SongService songService;

    @Test
    @DisplayName("GET /api/v1/songs/{id}: 200")
    void read_returns200() throws Exception {
        final SongResponse songResponse = new SongResponse(
                1L, "t", "a", 2020, MusicalKey.C_MAJOR, 120, Mood.UPBEAT,
                "ko", "pop", null, null, MetadataSource.MANUAL_SEED);
        given(songService.readById(1L)).willReturn(songResponse);

        mockMvc.perform(get("/api/v1/songs/{id}", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title", is("t")))
                .andExpect(jsonPath("$.artist", is("a")));
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
        final SongResponse songResponse = new SongResponse(
                1L, "벚꽃 엔딩", "버스커 버스커", 2012, MusicalKey.A_MAJOR, 132, Mood.EMOTIONAL,
                "ko", "ballad", "60540", null, MetadataSource.MANUAL_SEED);
        given(songService.searchByKeyword("벚꽃")).willReturn(List.of(songResponse));

        mockMvc.perform(get("/api/v1/songs").param("keyword", "벚꽃"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].title", is("벚꽃 엔딩")));
    }
}
