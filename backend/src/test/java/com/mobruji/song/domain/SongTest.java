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

    @Test
    @DisplayName("backfillFromAudioAnalysis: confidence 임계 통과 시 lowMidi/highMidi/difficulty/source/confidence 갱신")
    void backfillFromAudio_aboveThreshold_updatesAllFields() {
        // given: 수기 시드값 (사람이 대충 추정한 음역) — 기본 confidence = 1.0
        final Song song = Song.builder()
                .title("t").artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .lowMidi(60).highMidi(70) // EASY
                .build();
        // sanity
        assertThat(song.getDifficulty()).isEqualTo(Difficulty.EASY);
        assertThat(song.getMetadataConfidence()).isEqualTo(1.0);

        final AudioAnalysisResult result = new AudioAnalysisResult(
                57, 78, "C", 120.0, 200.0, 0.85, "analyze-py-0.1.0");

        // when
        final boolean changed = song.backfillFromAudioAnalysis(result, 0.6);

        // then: 분석값으로 덮어쓰고 source/difficulty/confidence 재계산
        assertThat(changed).isTrue();
        assertThat(song.getLowMidi()).isEqualTo(57);
        assertThat(song.getHighMidi()).isEqualTo(78);
        assertThat(song.getDifficulty()).isEqualTo(Difficulty.HARD); // highMidi=78 ≥ 76
        assertThat(song.getMetadataSource()).isEqualTo(MetadataSource.AUDIO_ANALYSIS);
        assertThat(song.getMetadataConfidence()).isEqualTo(0.85);
    }

    @Test
    @DisplayName("create: metadataConfidence 미명시 시 기본값 1.0 (MANUAL 신뢰도)")
    void create_withoutConfidence_defaultsToOne() {
        final Song song = Song.builder()
                .title("t").artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();

        assertThat(song.getMetadataConfidence()).isEqualTo(1.0);
        assertThat(song.getIsrc()).isNull();
    }

    @Test
    @DisplayName("create: metadataConfidence 0.0~1.0 범위 밖이면 IllegalArgumentException")
    void create_withConfidenceOutOfRange_throws() {
        assertThatThrownBy(() -> Song.builder()
                .title("t").artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .metadataConfidence(1.5)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metadataConfidence");
    }

    @Test
    @DisplayName("create: isrc 명시 시 그대로 저장")
    void create_withIsrc_isPersisted() {
        final Song song = Song.builder()
                .title("t").artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .isrc("KRA301700001")
                .build();

        assertThat(song.getIsrc()).isEqualTo("KRA301700001");
    }

    @Test
    @DisplayName("backfillFromAudioAnalysis: confidence 임계 미달이면 수기 값 보존, no-op")
    void backfillFromAudio_belowThreshold_preservesAll() {
        final Song song = Song.builder()
                .title("t").artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .lowMidi(60).highMidi(70)
                .build();

        final AudioAnalysisResult lowConf = new AudioAnalysisResult(
                40, 90, "C", 120.0, 200.0, 0.40, "analyze-py-0.1.0");

        // when
        final boolean changed = song.backfillFromAudioAnalysis(lowConf, 0.6);

        // then
        assertThat(changed).isFalse();
        assertThat(song.getLowMidi()).isEqualTo(60);
        assertThat(song.getHighMidi()).isEqualTo(70);
        assertThat(song.getMetadataSource()).isEqualTo(MetadataSource.MANUAL_SEED);
    }

    @Test
    @DisplayName("backfillFromAudioAnalysis: 분석값이 기존과 동일하면 source만 갱신")
    void backfillFromAudio_sameValues_updatesSourceOnly() {
        final Song song = Song.builder()
                .title("t").artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .lowMidi(60).highMidi(78)
                .build();

        final AudioAnalysisResult sameResult = new AudioAnalysisResult(
                60, 78, "C", 120.0, 200.0, 0.90, "analyze-py-0.1.0");

        final boolean changed = song.backfillFromAudioAnalysis(sameResult, 0.6);

        assertThat(changed).isTrue();
        assertThat(song.getMetadataSource()).isEqualTo(MetadataSource.AUDIO_ANALYSIS);
        assertThat(song.getLowMidi()).isEqualTo(60);
        assertThat(song.getHighMidi()).isEqualTo(78);
    }

    @Test
    @DisplayName("backfillFromAudioAnalysis: 임계값 범위 밖이면 IllegalArgumentException")
    void backfillFromAudio_invalidThreshold_throws() {
        final Song song = Song.builder()
                .title("t").artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
        final AudioAnalysisResult result = new AudioAnalysisResult(
                57, 78, null, null, 200.0, 0.7, "analyze-py-0.1.0");

        assertThatThrownBy(() -> song.backfillFromAudioAnalysis(result, 1.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidenceThreshold");
    }

    @Test
    @DisplayName("backfillFromAudioAnalysis: result null이면 NullPointerException")
    void backfillFromAudio_nullResult_throws() {
        final Song song = Song.builder()
                .title("t").artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();

        assertThatThrownBy(() -> song.backfillFromAudioAnalysis(null, 0.6))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("result");
    }

    @Test
    @DisplayName("create: energy 명시 시 그대로 저장")
    void create_withEnergy_isPersisted() {
        final Song song = Song.builder()
                .title("t").artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .energy(0.82f)
                .build();

        assertThat(song.getEnergy()).isEqualTo(0.82f);
    }

    @Test
    @DisplayName("create: energy 미명시 시 null (graceful degrade 대상)")
    void create_withoutEnergy_isNull() {
        final Song song = Song.builder()
                .title("t").artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();

        assertThat(song.getEnergy()).isNull();
    }

    @Test
    @DisplayName("create: energy 0.0~1.0 범위 밖이면 IllegalArgumentException")
    void create_withEnergyOutOfRange_throws() {
        assertThatThrownBy(() -> Song.builder()
                .title("t").artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .energy(1.5f)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("energy");
    }

    @Test
    @DisplayName("create: albumCoverUrl 명시 시 그대로 저장")
    void create_withAlbumCoverUrl_isPersisted() {
        final Song song = Song.builder()
                .title("t").artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .albumCoverUrl("https://example.com/cover.jpg")
                .build();

        assertThat(song.getAlbumCoverUrl()).isEqualTo("https://example.com/cover.jpg");
    }

    @Test
    @DisplayName("create: albumCoverUrl 미명시 시 null")
    void create_withoutAlbumCoverUrl_isNull() {
        final Song song = Song.builder()
                .title("t").artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();

        assertThat(song.getAlbumCoverUrl()).isNull();
    }

    @Test
    @DisplayName("backfillAlbumCoverUrl: 기존 값이 null 이면 새 URL 로 채우고 true 반환")
    void backfillAlbumCoverUrl_whenMissing_fillsAndReturnsTrue() {
        final Song song = Song.builder()
                .title("t").artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();

        final boolean changed = song.backfillAlbumCoverUrl("https://cdn.example.com/600x600bb.jpg");

        assertThat(changed).isTrue();
        assertThat(song.getAlbumCoverUrl()).isEqualTo("https://cdn.example.com/600x600bb.jpg");
    }

    @Test
    @DisplayName("backfillAlbumCoverUrl: 이미 값이 있으면 보존, false 반환 (큐레이터 수정 보호)")
    void backfillAlbumCoverUrl_whenPresent_preservesExisting() {
        final Song song = Song.builder()
                .title("t").artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .albumCoverUrl("https://curator.example.com/manual.jpg")
                .build();

        final boolean changed = song.backfillAlbumCoverUrl("https://cdn.example.com/600x600bb.jpg");

        assertThat(changed).isFalse();
        assertThat(song.getAlbumCoverUrl()).isEqualTo("https://curator.example.com/manual.jpg");
    }

    @Test
    @DisplayName("backfillAlbumCoverUrl: null 또는 blank 입력은 적용하지 않음")
    void backfillAlbumCoverUrl_nullOrBlank_isNoop() {
        final Song song = Song.builder()
                .title("t").artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();

        assertThat(song.backfillAlbumCoverUrl(null)).isFalse();
        assertThat(song.backfillAlbumCoverUrl("   ")).isFalse();
        assertThat(song.getAlbumCoverUrl()).isNull();
    }
}
