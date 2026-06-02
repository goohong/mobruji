package com.mobruji.recommendation.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.Mood;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;

/**
 * {@link TrendingSong} 불변식 가드 (#1488).
 */
class TrendingSongTest {

    @Test
    @DisplayName("정상 생성")
    void validConstruction() {
        assertThatCode(() -> new TrendingSong(song(), 1, 3L, 2.5)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("song null → 예외")
    void rejectsNullSong() {
        assertThatThrownBy(() -> new TrendingSong(null, 1, 1L, 1.0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("rankPosition < 1 → 예외")
    void rejectsNonPositiveRank() {
        assertThatThrownBy(() -> new TrendingSong(song(), 0, 1L, 1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rankPosition");
    }

    @Test
    @DisplayName("appearanceCount < 1 → 예외")
    void rejectsNonPositiveAppearanceCount() {
        assertThatThrownBy(() -> new TrendingSong(song(), 1, 0L, 1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("appearanceCount");
    }

    private static Song song() {
        return Song.builder()
                .title("벚꽃 엔딩").artist("버스커 버스커")
                .keyOriginal(MusicalKey.A_MAJOR)
                .mood(Mood.UPBEAT)
                .genre("발라드")
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
    }
}
