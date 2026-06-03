package com.mobruji.song.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SongAnalysisProfileTest {

    @Test
    @DisplayName("from: 분석 필드가 모두 채워진 곡 → 프로파일에 그대로 매핑")
    void from_fullSong_mapsAllFields() {
        final Song song = Song.builder()
                .title("벚꽃 엔딩").artist("버스커 버스커")
                .keyOriginal(MusicalKey.A_MAJOR).bpm(132).mood(Mood.EMOTIONAL)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .metadataConfidence(0.9)
                .lowMidi(57).highMidi(76)
                .energy(0.42f)
                .build();

        final SongAnalysisProfile profile = SongAnalysisProfile.from(song);

        assertThat(profile.lowMidi()).isEqualTo(57);
        assertThat(profile.highMidi()).isEqualTo(76);
        assertThat(profile.keyOriginal()).isEqualTo(MusicalKey.A_MAJOR);
        assertThat(profile.difficulty()).isEqualTo(Difficulty.HARD);
        assertThat(profile.mood()).isEqualTo(Mood.EMOTIONAL);
        assertThat(profile.energy()).isEqualTo(0.42f);
        assertThat(profile.metadataConfidence()).isEqualTo(0.9);
    }

    @Test
    @DisplayName("from: nullable 분석 필드 미적재 → null 보존 (소비자 graceful degrade 입력)")
    void from_missingOptionalFields_preservesNull() {
        final Song song = Song.builder()
                .title("t").artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();

        final SongAnalysisProfile profile = SongAnalysisProfile.from(song);

        assertThat(profile.lowMidi()).isNull();
        assertThat(profile.highMidi()).isNull();
        assertThat(profile.difficulty()).isNull();
        assertThat(profile.mood()).isNull();
        assertThat(profile.energy()).isNull();
        assertThat(profile.keyOriginal()).isEqualTo(MusicalKey.C_MAJOR);
    }

    @Test
    @DisplayName("from: null 곡 → NPE")
    void from_nullSong_throws() {
        assertThatThrownBy(() -> SongAnalysisProfile.from(null))
                .isInstanceOf(NullPointerException.class);
    }
}
