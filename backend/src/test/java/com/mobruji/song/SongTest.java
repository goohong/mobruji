package com.mobruji.song;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SongTest {

    @Test
    @DisplayName("필수 필드로 create하면 정상 생성된다")
    void create_withRequiredFields_createsInstance() {
        final Song song = Song.builder()
                .title("벚꽃 엔딩")
                .artist("버스커 버스커")
                .releaseYear(2012)
                .keyOriginal(MusicalKey.A_MAJOR)
                .bpm(132)
                .mood(Mood.EMOTIONAL)
                .language("ko")
                .genre("발라드")
                .tjNumber("60540")
                .kyNumber("84226")
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();

        assertThat(song.getTitle()).isEqualTo("벚꽃 엔딩");
        assertThat(song.getArtist()).isEqualTo("버스커 버스커");
        assertThat(song.getKeyOriginal()).isEqualTo(MusicalKey.A_MAJOR);
        assertThat(song.getMetadataSource()).isEqualTo(MetadataSource.MANUAL_SEED);
        assertThat(song.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("title null이면 NullPointerException")
    void create_withNullTitle_throws() {
        assertThatThrownBy(() -> Song.builder()
                .title(null)
                .artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("title");
    }

    @Test
    @DisplayName("title 빈 문자열이면 IllegalArgumentException")
    void create_withBlankTitle_throws() {
        assertThatThrownBy(() -> Song.builder()
                .title("   ")
                .artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    @DisplayName("BPM 범위 밖이면 IllegalArgumentException")
    void create_withOutOfRangeBpm_throws() {
        assertThatThrownBy(() -> Song.builder()
                .title("t")
                .artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .bpm(500)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bpm");
    }

    @Test
    @DisplayName("BPM null 허용")
    void create_withNullBpm_ok() {
        final Song song = Song.builder()
                .title("t")
                .artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .bpm(null)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
        assertThat(song.getBpm()).isNull();
    }
}
