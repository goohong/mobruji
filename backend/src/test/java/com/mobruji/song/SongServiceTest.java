package com.mobruji.song;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mobruji.song.dto.SongResponse;

@ExtendWith(MockitoExtension.class)
class SongServiceTest {

    @Mock
    private SongRepository songRepository;

    @InjectMocks
    private SongService songService;

    @Test
    @DisplayName("readById: 존재하면 응답 반환")
    void readById_found_returnsResponse() {
        // given
        final Song song = Song.builder()
                .title("t").artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
        given(songRepository.findById(1L)).willReturn(Optional.of(song));

        // when
        final SongResponse songResponse = songService.readById(1L);

        // then
        assertThat(songResponse.title()).isEqualTo("t");
    }

    @Test
    @DisplayName("readById: 없으면 SongNotFoundException")
    void readById_notFound_throws() {
        given(songRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> songService.readById(999L))
                .isInstanceOf(SongNotFoundException.class)
                .hasMessageContaining("999");
    }

    @Test
    @DisplayName("searchByKeyword: 빈 키워드는 빈 리스트 반환")
    void searchByKeyword_emptyKeyword_returnsEmpty() {
        assertThat(songService.searchByKeyword("")).isEmpty();
        assertThat(songService.searchByKeyword("   ")).isEmpty();
        assertThat(songService.searchByKeyword(null)).isEmpty();
    }

    @Test
    @DisplayName("searchByKeyword: 유효 키워드면 매핑된 응답 리스트 반환")
    void searchByKeyword_valid_returnsResponses() {
        final Song song = Song.builder()
                .title("벚꽃 엔딩").artist("버스커 버스커")
                .keyOriginal(MusicalKey.A_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
        given(songRepository.searchByKeyword("벚꽃")).willReturn(List.of(song));

        final List<SongResponse> songResponses = songService.searchByKeyword("벚꽃");

        assertThat(songResponses).hasSize(1);
        assertThat(songResponses.get(0).title()).isEqualTo("벚꽃 엔딩");
    }
}
