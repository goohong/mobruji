package com.mobruji.recommendation.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.recommendation.domain.RecommendationResult;
import com.mobruji.recommendation.domain.ScoreBreakdown;
import com.mobruji.recommendation.domain.ScoredRecommendation;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;

class RecommendationResponseTest {

    @Test
    @DisplayName("from: RecommendationResult 도메인을 응답 DTO로 변환 (Song → SongResponse 매핑 포함)")
    void from_mapsDomainResultToDto() {
        // given
        final Song song = Song.builder()
                .title("t").artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .lowMidi(60).highMidi(72)
                .build();
        final ScoreBreakdown breakdown = new ScoreBreakdown(1.0, 0.95, 0.0, 1.0, 1.0, 0.8, 0.0);
        final RecommendationResult recommendationResult = new RecommendationResult(
                42L,
                List.of(new ScoredRecommendation(song, 0.87, "음역 적합", 1, breakdown)));

        // when
        final RecommendationResponse recommendationResponse = RecommendationResponse.from(recommendationResult);

        // then
        assertThat(recommendationResponse.requestId()).isEqualTo(42L);
        assertThat(recommendationResponse.recommendations()).hasSize(1);
        final RecommendedSongResponse recommendedSongResponse = recommendationResponse.recommendations().get(0);
        assertThat(recommendedSongResponse.song().title()).isEqualTo("t");
        assertThat(recommendedSongResponse.score()).isEqualTo(0.87);
        assertThat(recommendedSongResponse.matchReason()).isEqualTo("음역 적합");
        assertThat(recommendedSongResponse.rankPosition()).isEqualTo(1);
        // breakdown 6신호가 그대로 노출되어야 함 (fe 14 matchReason 펼침 UX, v2 tempoMatch 포함)
        assertThat(recommendedSongResponse.breakdown()).isNotNull();
        assertThat(recommendedSongResponse.breakdown().keyMatch()).isEqualTo(1.0);
        assertThat(recommendedSongResponse.breakdown().rangeFit()).isEqualTo(0.95);
        assertThat(recommendedSongResponse.breakdown().genreMatch()).isEqualTo(0.0);
        assertThat(recommendedSongResponse.breakdown().moodMatch()).isEqualTo(1.0);
        assertThat(recommendedSongResponse.breakdown().popularity()).isEqualTo(1.0);
        assertThat(recommendedSongResponse.breakdown().tempoMatch()).isEqualTo(0.8);
    }

    @Test
    @DisplayName("from: breakdown 없는 ScoredRecommendation(과거 추천 재조회)도 변환 가능 — breakdown=null")
    void from_breakdownNull_passesThroughAsNull() {
        final Song song = Song.builder()
                .title("t").artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
        // breakdown 없는 편의 생성자
        final RecommendationResult recommendationResult = new RecommendationResult(
                42L,
                List.of(new ScoredRecommendation(song, 0.5, "전반적 매칭", 1)));

        final RecommendationResponse recommendationResponse = RecommendationResponse.from(recommendationResult);

        assertThat(recommendationResponse.recommendations().get(0).breakdown()).isNull();
    }

    @Test
    @DisplayName("from: 빈 추천 리스트도 변환 가능")
    void from_emptyRecommendations() {
        final RecommendationResult recommendationResult = new RecommendationResult(7L, List.of());

        final RecommendationResponse recommendationResponse = RecommendationResponse.from(recommendationResult);

        assertThat(recommendationResponse.requestId()).isEqualTo(7L);
        assertThat(recommendationResponse.recommendations()).isEmpty();
    }
}
