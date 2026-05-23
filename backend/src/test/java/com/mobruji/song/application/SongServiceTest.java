package com.mobruji.song.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.song.domain.SongNotFoundException;
import com.mobruji.song.infrastructure.SongRepository;

@ExtendWith(MockitoExtension.class)
class SongServiceTest {

    @Mock
    private SongRepository songRepository;

    @InjectMocks
    private SongService songService;

    @Test
    @DisplayName("readById: 존재하면 도메인 객체 반환")
    void readById_found_returnsSong() {
        // given
        final Song song = Song.builder()
                .title("t").artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
        given(songRepository.findById(1L)).willReturn(Optional.of(song));

        // when
        final Song readSong = songService.readById(1L);

        // then
        assertThat(readSong.getTitle()).isEqualTo("t");
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
    @DisplayName("searchByKeyword: 유효 키워드면 매핑된 도메인 리스트 반환")
    void searchByKeyword_valid_returnsSongs() {
        final Song song = Song.builder()
                .title("벚꽃 엔딩").artist("버스커 버스커")
                .keyOriginal(MusicalKey.A_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
        given(songRepository.searchByKeyword("벚꽃")).willReturn(List.of(song));

        final List<Song> songs = songService.searchByKeyword("벚꽃");

        assertThat(songs).hasSize(1);
        assertThat(songs.get(0).getTitle()).isEqualTo("벚꽃 엔딩");
    }

    @Test
    @DisplayName("searchByKeyword: 키워드 앞뒤 공백은 trim 후 repository에 전달")
    void searchByKeyword_trimsBeforeQuery() {
        final Song song = Song.builder()
                .title("벚꽃 엔딩").artist("버스커 버스커")
                .keyOriginal(MusicalKey.A_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
        given(songRepository.searchByKeyword("벚꽃")).willReturn(List.of(song));

        final List<Song> songs = songService.searchByKeyword("  벚꽃  ");

        assertThat(songs).hasSize(1);
        then(songRepository).should().searchByKeyword("벚꽃");
    }

    @Test
    @DisplayName("searchByKeyword: 유효 키워드이지만 결과 없으면 빈 리스트 반환")
    void searchByKeyword_noMatch_returnsEmptyList() {
        given(songRepository.searchByKeyword("없는곡")).willReturn(List.of());

        final List<Song> songs = songService.searchByKeyword("없는곡");

        assertThat(songs).isEmpty();
    }

    @Test
    @DisplayName("searchByKeyword: 다중 결과는 repository 순서를 보존")
    void searchByKeyword_multipleResults_preservesOrder() {
        final Song first = Song.builder()
                .title("벚꽃 엔딩").artist("버스커 버스커")
                .keyOriginal(MusicalKey.A_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
        final Song second = Song.builder()
                .title("벚꽃 길").artist("아이유")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
        given(songRepository.searchByKeyword("벚꽃")).willReturn(List.of(first, second));

        final List<Song> songs = songService.searchByKeyword("벚꽃");

        assertThat(songs).extracting(Song::getTitle)
                .containsExactly("벚꽃 엔딩", "벚꽃 길");
    }

    @Test
    @DisplayName("searchByKeyword: 탭/개행만 있는 키워드도 blank로 처리해 빈 리스트 반환")
    void searchByKeyword_tabAndNewline_treatedAsBlank() {
        assertThat(songService.searchByKeyword("\t\n  ")).isEmpty();
    }

    @Test
    @DisplayName("searchByKeyword: blank 키워드(빈/공백/null)는 repository 호출 자체를 차단 — DoS 방지 회귀 가드 (spec §5-2)")
    void searchByKeyword_blank_doesNotCallRepository() {
        songService.searchByKeyword(null);
        songService.searchByKeyword("");
        songService.searchByKeyword("   ");
        songService.searchByKeyword("\t\n");

        then(songRepository).should(never()).searchByKeyword(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("readById: repository에서 받은 도메인 객체를 그대로 위임")
    void readById_delegatesRepositoryInstance() {
        final Song song = Song.builder()
                .title("t").artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
        given(songRepository.findById(7L)).willReturn(Optional.of(song));

        final Song readSong = songService.readById(7L);

        assertThat(readSong).isSameAs(song);
    }
}
