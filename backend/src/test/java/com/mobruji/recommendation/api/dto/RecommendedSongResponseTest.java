package com.mobruji.recommendation.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.recommendation.domain.ScoreBreakdown;
import com.mobruji.recommendation.domain.ScoredRecommendation;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;

class RecommendedSongResponseTest {

    @Test
    @DisplayName("from: Song + ScoreBreakdown 포함 ScoredRecommendation → DTO 매핑 (모든 필드 보존)")
    void from_mapsAllFieldsIncludingBreakdown() {
        final Song song = Song.builder()
                .title("러브 다이브").artist("아이브")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .lowMidi(57).highMidi(81)
                .build();
        final ScoreBreakdown breakdown = new ScoreBreakdown(1.0, 0.92, 0.75, 0.6, 0.5, 0.85);
        final ScoredRecommendation scoredRecommendation = new ScoredRecommendation(
                song, 0.78, "음역 적합", 2, breakdown);

        final RecommendedSongResponse recommendedSongResponse = RecommendedSongResponse.from(scoredRecommendation);

        assertThat(recommendedSongResponse.song().title()).isEqualTo("러브 다이브");
        assertThat(recommendedSongResponse.song().artist()).isEqualTo("아이브");
        assertThat(recommendedSongResponse.score()).isEqualTo(0.78);
        assertThat(recommendedSongResponse.matchReason()).isEqualTo("음역 적합");
        assertThat(recommendedSongResponse.rankPosition()).isEqualTo(2);
        assertThat(recommendedSongResponse.breakdown()).isNotNull();
        assertThat(recommendedSongResponse.breakdown().keyMatch()).isEqualTo(1.0);
        assertThat(recommendedSongResponse.breakdown().rangeFit()).isEqualTo(0.92);
        assertThat(recommendedSongResponse.breakdown().genreMatch()).isEqualTo(0.75);
        assertThat(recommendedSongResponse.breakdown().moodMatch()).isEqualTo(0.6);
        assertThat(recommendedSongResponse.breakdown().popularity()).isEqualTo(0.5);
        assertThat(recommendedSongResponse.breakdown().tempoMatch()).isEqualTo(0.85);
    }

    @Test
    @DisplayName("from: breakdown null (과거 추천 재조회 경로) → DTO 의 breakdown 도 null 로 통과")
    void from_nullBreakdown_passesThroughAsNull() {
        final Song song = Song.builder()
                .title("t").artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
        // 4-arg 편의 생성자 → breakdown=null
        final ScoredRecommendation scoredRecommendation = new ScoredRecommendation(song, 0.5, "전반적 매칭", 1);

        final RecommendedSongResponse recommendedSongResponse = RecommendedSongResponse.from(scoredRecommendation);

        assertThat(recommendedSongResponse.breakdown()).isNull();
        assertThat(recommendedSongResponse.score()).isEqualTo(0.5);
        assertThat(recommendedSongResponse.matchReason()).isEqualTo("전반적 매칭");
        assertThat(recommendedSongResponse.rankPosition()).isEqualTo(1);
    }

    @Test
    @DisplayName("from: low/high MIDI 가 null 인 Song 도 DTO 매핑 가능 (lowestNoteName/highestNoteName 도 null)")
    void from_songWithNullMidi_mapsNoteNamesAsNull() {
        final Song song = Song.builder()
                .title("t").artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                // lowMidi/highMidi 미지정 → null
                .build();
        final ScoredRecommendation scoredRecommendation = new ScoredRecommendation(song, 0.4, "매칭", 3);

        final RecommendedSongResponse recommendedSongResponse = RecommendedSongResponse.from(scoredRecommendation);

        assertThat(recommendedSongResponse.song().lowMidi()).isNull();
        assertThat(recommendedSongResponse.song().highMidi()).isNull();
        assertThat(recommendedSongResponse.song().lowestNoteName()).isNull();
        assertThat(recommendedSongResponse.song().highestNoteName()).isNull();
    }
}
