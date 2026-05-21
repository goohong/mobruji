package com.mobruji.song.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.song.domain.Difficulty;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;

class SongResponseTest {

    @Test
    @DisplayName("from: Song 도메인을 응답 DTO로 변환 + 음역 → 노트 이름 매핑")
    void from_mapsDomainToDtoWithNoteNames() {
        // given
        final Song song = Song.builder()
                .title("벚꽃 엔딩").artist("버스커 버스커").releaseYear(2012)
                .keyOriginal(MusicalKey.A_MAJOR).bpm(132)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .lowMidi(60).highMidi(76)
                .build();

        // when
        final SongResponse songResponse = SongResponse.from(song);

        // then
        assertThat(songResponse.title()).isEqualTo("벚꽃 엔딩");
        assertThat(songResponse.artist()).isEqualTo("버스커 버스커");
        assertThat(songResponse.lowMidi()).isEqualTo(60);
        assertThat(songResponse.highMidi()).isEqualTo(76);
        assertThat(songResponse.lowestNoteName()).isEqualTo("C4");
        assertThat(songResponse.highestNoteName()).isEqualTo("E5");
        assertThat(songResponse.difficulty()).isEqualTo(Difficulty.HARD);
    }

    @Test
    @DisplayName("from: lowMidi/highMidi가 null이면 노트 이름도 null")
    void from_whenMidiMissing_noteNamesAreNull() {
        // given
        final Song song = Song.builder()
                .title("t").artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();

        // when
        final SongResponse songResponse = SongResponse.from(song);

        // then
        assertThat(songResponse.lowMidi()).isNull();
        assertThat(songResponse.highMidi()).isNull();
        assertThat(songResponse.lowestNoteName()).isNull();
        assertThat(songResponse.highestNoteName()).isNull();
    }
}
