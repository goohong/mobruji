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
        final ScoreBreakdown breakdown = new ScoreBreakdown(1.0, 0.92, 0.75, 0.6, 0.5, 0.85, 0.0);
        final ScoredRecommendation scoredRecommendation = new ScoredRecommendation(
                song, 0.78, "음역 적합", 2, breakdown);

        final RecommendedSongResponse recommendedSongResponse = RecommendedSongResponse.from(scoredRecommendation);

        assertThat(recommendedSongResponse.song().title()).isEqualTo("러브 다이브");
        assertThat(recommendedSongResponse.song().artist()).isEqualTo("아이브");
        assertThat(recommendedSongResponse.score()).isEqualTo(0.78);
        assertThat(recommendedSongResponse.matchReason()).isEqualTo("음역 적합");
        // 설명가능성(#1484): voiceFit은 breakdown.rangeFit 노출, 사유는 적합도 구간별 한국어
        assertThat(recommendedSongResponse.voiceFit()).isEqualTo(0.92);
        assertThat(recommendedSongResponse.voiceFitReason()).isEqualTo("원곡 키가 음역대에 아주 잘 맞아요");
        // 분위기 변별력(#1485): moodFit은 breakdown.moodMatch 노출, 사유는 유사도 구간별 한국어
        assertThat(recommendedSongResponse.moodFit()).isEqualTo(0.6);
        assertThat(recommendedSongResponse.moodFitReason()).isEqualTo("요청하신 분위기와 잘 어울려요");
        // 연습 지원(#1494): 곡 음역(57~81) → HARD + 최고음 A5 사유
        assertThat(recommendedSongResponse.practiceDifficulty()).isEqualTo(com.mobruji.song.domain.Difficulty.HARD);
        assertThat(recommendedSongResponse.practiceDifficultyReason())
                .isEqualTo("최고음 A5, 고음·넓은 음역이라 도전적인 곡이에요");
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
        // 설명가능성(#1484/#1485): breakdown 없는 재조회 경로면 voiceFit/moodFit 도 모두 null
        assertThat(recommendedSongResponse.voiceFit()).isNull();
        assertThat(recommendedSongResponse.voiceFitReason()).isNull();
        assertThat(recommendedSongResponse.moodFit()).isNull();
        assertThat(recommendedSongResponse.moodFitReason()).isNull();
        // 연습 지원(#1494): 곡 속성 파생이라 재조회 경로에서도 채워진다. 음역 미보유 → 난이도 null + graceful 사유
        assertThat(recommendedSongResponse.practiceDifficulty()).isNull();
        assertThat(recommendedSongResponse.practiceDifficultyReason())
                .isEqualTo("아직 음역대 분석 정보가 없어 난이도를 가늠하기 어려워요");
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
