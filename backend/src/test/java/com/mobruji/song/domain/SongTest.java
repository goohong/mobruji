package com.mobruji.song.domain;

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

    @Test
    @DisplayName("deriveDifficulty: highMidi 76 (E5)은 HARD")
    void deriveDifficulty_high76_isHard() {
        // given: 경계값 highMidi=HIGH_HARD_THRESHOLD, span=10 (span 조건은 미충족)
        // when
        final Difficulty difficulty = Song.deriveDifficulty(66, 76);
        // then
        assertThat(difficulty).isEqualTo(Difficulty.HARD);
    }

    @Test
    @DisplayName("deriveDifficulty: highMidi 75 + span 17은 HARD (span 조건)")
    void deriveDifficulty_spanAtThreshold_isHard() {
        // given: highMidi<76 이지만 span=17 (=SPAN_HARD_THRESHOLD)
        // when
        final Difficulty difficulty = Song.deriveDifficulty(58, 75);
        // then
        assertThat(difficulty).isEqualTo(Difficulty.HARD);
    }

    @Test
    @DisplayName("deriveDifficulty: highMidi 71 (B4) + span 11은 NORMAL")
    void deriveDifficulty_high71SpanSmall_isNormal() {
        // given: 경계값 highMidi=HIGH_NORMAL_THRESHOLD, span<17
        // when
        final Difficulty difficulty = Song.deriveDifficulty(60, 71);
        // then
        assertThat(difficulty).isEqualTo(Difficulty.NORMAL);
    }

    @Test
    @DisplayName("deriveDifficulty: highMidi 70 (A#4) + span 10은 EASY")
    void deriveDifficulty_high70_isEasy() {
        // given: highMidi=70<71, span<17
        // when
        final Difficulty difficulty = Song.deriveDifficulty(60, 70);
        // then
        assertThat(difficulty).isEqualTo(Difficulty.EASY);
    }

    @Test
    @DisplayName("create: lowMidi/highMidi 둘 다 있으면 difficulty 자동 분류")
    void create_withMidiRange_autoDerivesDifficulty() {
        // given/when: highMidi 78 = HARD 임계 초과
        final Song song = Song.builder()
                .title("t")
                .artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .lowMidi(60)
                .highMidi(78)
                .build();
        // then
        assertThat(song.getDifficulty()).isEqualTo(Difficulty.HARD);
        assertThat(song.getLowMidi()).isEqualTo(60);
        assertThat(song.getHighMidi()).isEqualTo(78);
    }

    @Test
    @DisplayName("create: lowMidi > highMidi이면 IllegalArgumentException")
    void create_withInvertedMidi_throws() {
        assertThatThrownBy(() -> Song.builder()
                .title("t")
                .artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .lowMidi(80)
                .highMidi(60)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lowMidi");
    }

    @Test
    @DisplayName("create: MIDI 미지정 시 difficulty도 null (자동 분류 스킵)")
    void create_withoutMidi_leavesDifficultyNull() {
        final Song song = Song.builder()
                .title("t")
                .artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
        assertThat(song.getDifficulty()).isNull();
        assertThat(song.getLowMidi()).isNull();
        assertThat(song.getHighMidi()).isNull();
    }

    @Test
    @DisplayName("backfillMissingFields: null 필드를 채우고 difficulty 자동 분류")
    void backfill_fillsNullFields_andDerivesDifficulty() {
        // given: MIDI/difficulty 미지정 상태
        final Song song = Song.builder()
                .title("t").artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
        assertThat(song.getDifficulty()).isNull();

        // when: lowMidi/highMidi 백필 (difficulty는 자동 분류 위임)
        final boolean changed = song.backfillMissingFields(60, 78, null);

        // then
        assertThat(changed).isTrue();
        assertThat(song.getLowMidi()).isEqualTo(60);
        assertThat(song.getHighMidi()).isEqualTo(78);
        assertThat(song.getDifficulty()).isEqualTo(Difficulty.HARD);
    }

    @Test
    @DisplayName("backfillMissingFields: 이미 값이 있는 필드는 보존")
    void backfill_preservesExistingValues() {
        // given: lowMidi/highMidi 모두 채워진 상태 (difficulty도 자동 분류됨)
        final Song song = Song.builder()
                .title("t").artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .lowMidi(57).highMidi(76) // 사용자 수정값 가정
                .build();
        assertThat(song.getDifficulty()).isEqualTo(Difficulty.HARD);

        // when: 다른 값으로 backfill 시도
        final boolean changed = song.backfillMissingFields(50, 80, Difficulty.EASY);

        // then: 원래 값 유지, 변경 없음
        assertThat(changed).isFalse();
        assertThat(song.getLowMidi()).isEqualTo(57);
        assertThat(song.getHighMidi()).isEqualTo(76);
        assertThat(song.getDifficulty()).isEqualTo(Difficulty.HARD);
    }

    @Test
    @DisplayName("backfillMissingFields: 일부만 null이면 그 필드만 채움")
    void backfill_partialNull_fillsOnlyNullField() {
        // given: lowMidi만 null, highMidi/difficulty는 있음 (드문 케이스지만 도메인은 방어해야)
        final Song song = Song.builder()
                .title("t").artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
        // when: lowMidi만 백필
        final boolean changed = song.backfillMissingFields(60, null, null);

        // then
        assertThat(changed).isTrue();
        assertThat(song.getLowMidi()).isEqualTo(60);
        assertThat(song.getHighMidi()).isNull();
        // lowMidi/highMidi 둘 다 채워지지 않아 difficulty는 여전히 null
        assertThat(song.getDifficulty()).isNull();
    }

    @Test
    @DisplayName("backfillMissingFields: 모든 인자 null이면 no-op")
    void backfill_allNull_isNoop() {
        final Song song = Song.builder()
                .title("t").artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();

        final boolean changed = song.backfillMissingFields(null, null, null);

        assertThat(changed).isFalse();
        assertThat(song.getLowMidi()).isNull();
    }

    @Test
    @DisplayName("backfillMissingFields: difficulty 명시되면 자동 분류보다 우선")
    void backfill_explicitDifficulty_takesPrecedence() {
        // given: MIDI 없이 difficulty만 명시 백필
        final Song song = Song.builder()
                .title("t").artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();

        // when
        final boolean changed = song.backfillMissingFields(null, null, Difficulty.NORMAL);

        // then
        assertThat(changed).isTrue();
        assertThat(song.getDifficulty()).isEqualTo(Difficulty.NORMAL);
    }
}
