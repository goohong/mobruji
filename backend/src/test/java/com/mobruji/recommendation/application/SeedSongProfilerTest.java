package com.mobruji.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.Mood;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;

/**
 * {@link SeedSongProfiler} 단위 가드 — 부른 곡(seed)에서 추천 입력(음역대·분위기·BPM)을 도출하는 규칙.
 *
 * <p>이슈 #1486 (roadmap P-B). 결정성(같은 seed → 같은 프로필)과
 * 폴백(음역 도출 불가/ mood·BPM 전부 부재) 경계를 검증한다.
 */
class SeedSongProfilerTest {

    private static final String SESSION_ID = "550e8400-e29b-41d4-a716-446655440000";

    private final SeedSongProfiler seedSongProfiler = new SeedSongProfiler();

    @Nested
    @DisplayName("음역대 도출")
    class VoiceRange {

        @Test
        @DisplayName("measured midi(lowMidi/highMidi)가 있으면 그 값을 평균한다")
        void averagesMeasuredMidi() {
            // given: 두 곡의 측정 음역 평균 → low (50+60)/2=55, high (70+80)/2=75
            final List<Song> seeds = List.of(
                    songWithMidi(50, 70),
                    songWithMidi(60, 80));

            // when
            final CreateRecommendationCommand command = seedSongProfiler.profile(SESSION_ID, seeds, List.of());

            // then
            assertThat(command.voiceRangeLow()).isEqualTo(55);
            assertThat(command.voiceRangeHigh()).isEqualTo(75);
        }

        @Test
        @DisplayName("measured midi가 없으면 키 root±7로 추정한다")
        void fallsBackToKeyRoot() {
            // given: C_MAJOR root=60 → [53, 67]
            final List<Song> seeds = List.of(songWithKey(MusicalKey.C_MAJOR));

            // when
            final CreateRecommendationCommand command = seedSongProfiler.profile(SESSION_ID, seeds, List.of());

            // then
            assertThat(command.voiceRangeLow()).isEqualTo(53);
            assertThat(command.voiceRangeHigh()).isEqualTo(67);
        }

        @Test
        @DisplayName("UNKNOWN 키 + midi 부재 곡뿐이면 표준 음역으로 폴백한다")
        void fallsBackToNeutralWhenUndeterminable() {
            // given
            final List<Song> seeds = List.of(songWithKey(MusicalKey.UNKNOWN));

            // when
            final CreateRecommendationCommand command = seedSongProfiler.profile(SESSION_ID, seeds, List.of());

            // then
            assertThat(command.voiceRangeLow()).isEqualTo(SeedSongProfiler.NEUTRAL_VOICE_LOW);
            assertThat(command.voiceRangeHigh()).isEqualTo(SeedSongProfiler.NEUTRAL_VOICE_HIGH);
        }

        @Test
        @DisplayName("도출 불가 곡은 평균에서 제외하고 나머지로 계산한다")
        void skipsUndeterminableSongs() {
            // given: UNKNOWN(제외) + C_MAJOR([53,67]) → C_MAJOR만으로 [53,67]
            final List<Song> seeds = List.of(
                    songWithKey(MusicalKey.UNKNOWN),
                    songWithKey(MusicalKey.C_MAJOR));

            // when
            final CreateRecommendationCommand command = seedSongProfiler.profile(SESSION_ID, seeds, List.of());

            // then
            assertThat(command.voiceRangeLow()).isEqualTo(53);
            assertThat(command.voiceRangeHigh()).isEqualTo(67);
        }
    }

    @Nested
    @DisplayName("분위기 도출")
    class DominantMood {

        @Test
        @DisplayName("최빈 mood를 선택한다")
        void picksMostFrequent() {
            // given: UPBEAT 2, EMOTIONAL 1
            final List<Song> seeds = List.of(
                    songWithMood(Mood.UPBEAT),
                    songWithMood(Mood.UPBEAT),
                    songWithMood(Mood.EMOTIONAL));

            // when
            final CreateRecommendationCommand command = seedSongProfiler.profile(SESSION_ID, seeds, List.of());

            // then
            assertThat(command.mood()).isEqualTo(Mood.UPBEAT);
        }

        @Test
        @DisplayName("동률이면 enum 정의 순서가 앞선 mood를 선택한다 (결정성)")
        void breaksTieByEnumOrdinal() {
            // given: UPBEAT(ordinal 0) 1, NOSTALGIC 1 — 동률 → UPBEAT
            final List<Song> seeds = List.of(
                    songWithMood(Mood.NOSTALGIC),
                    songWithMood(Mood.UPBEAT));

            // when
            final CreateRecommendationCommand command = seedSongProfiler.profile(SESSION_ID, seeds, List.of());

            // then
            assertThat(command.mood()).isEqualTo(Mood.values()[0]);
        }

        @Test
        @DisplayName("곡 mood가 전부 부재면 null")
        void nullWhenNoMood() {
            // given
            final List<Song> seeds = List.of(songWithKey(MusicalKey.C_MAJOR));

            // when
            final CreateRecommendationCommand command = seedSongProfiler.profile(SESSION_ID, seeds, List.of());

            // then
            assertThat(command.mood()).isNull();
        }
    }

    @Nested
    @DisplayName("BPM 도출")
    class AverageBpm {

        @Test
        @DisplayName("비-null BPM 평균(반올림)을 선호 BPM으로 둔다")
        void averagesNonNullBpm() {
            // given: 120, 130 → 125
            final List<Song> seeds = List.of(songWithBpm(120), songWithBpm(130));

            // when
            final CreateRecommendationCommand command = seedSongProfiler.profile(SESSION_ID, seeds, List.of());

            // then
            assertThat(command.preferredBpm()).isEqualTo(125);
        }

        @Test
        @DisplayName("BPM이 전부 부재면 null")
        void nullWhenNoBpm() {
            // given
            final List<Song> seeds = List.of(songWithKey(MusicalKey.C_MAJOR));

            // when
            final CreateRecommendationCommand command = seedSongProfiler.profile(SESSION_ID, seeds, List.of());

            // then
            assertThat(command.preferredBpm()).isNull();
        }
    }

    @Test
    @DisplayName("excludeSongIds는 그대로 커맨드에 전달된다 (seed 합산은 service 책임)")
    void passesThroughExcludeIds() {
        // given
        final List<Song> seeds = List.of(songWithKey(MusicalKey.C_MAJOR));

        // when
        final CreateRecommendationCommand command = seedSongProfiler.profile(SESSION_ID, seeds, List.of(7L, 8L));

        // then
        assertThat(command.excludeSongIds()).containsExactly(7L, 8L);
    }

    @Test
    @DisplayName("빈 seed 리스트는 거부한다")
    void rejectsEmptySeeds() {
        assertThatThrownBy(() -> seedSongProfiler.profile(SESSION_ID, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Song songWithMidi(final int lowMidi, final int highMidi) {
        return Song.builder()
                .title("t").artist("a").keyOriginal(MusicalKey.C_MAJOR)
                .lowMidi(lowMidi).highMidi(highMidi)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
    }

    private static Song songWithKey(final MusicalKey key) {
        return Song.builder()
                .title("t").artist("a").keyOriginal(key)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
    }

    private static Song songWithMood(final Mood mood) {
        return Song.builder()
                .title("t").artist("a").keyOriginal(MusicalKey.C_MAJOR).mood(mood)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
    }

    private static Song songWithBpm(final int bpm) {
        return Song.builder()
                .title("t").artist("a").keyOriginal(MusicalKey.C_MAJOR).bpm(bpm)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
    }
}
