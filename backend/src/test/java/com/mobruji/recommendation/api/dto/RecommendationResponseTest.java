package com.mobruji.recommendation.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.recommendation.domain.RecommendationResult;
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
        final RecommendationResult recommendationResult = new RecommendationResult(
                42L,
                List.of(new ScoredRecommendation(song, 0.87, "음역 적합", 1)));

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
