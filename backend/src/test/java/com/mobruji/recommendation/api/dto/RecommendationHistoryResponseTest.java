package com.mobruji.recommendation.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.recommendation.domain.RecommendationRequestEntity;
import com.mobruji.recommendation.domain.RecommendationResult;
import com.mobruji.recommendation.domain.ScoreBreakdown;
import com.mobruji.recommendation.domain.ScoredRecommendation;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.Mood;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;

class RecommendationHistoryResponseTest {

    @Test
    @DisplayName("from: 요청 엔티티 + 결과 도메인 → DTO 매핑 (mood=UPBEAT, preferredBpm=130)")
    void from_mapsAllFieldsWithMoodAndBpm() {
        final RecommendationRequestEntity recommendationRequestEntity = RecommendationRequestEntity.create(
                "session-x", 48, 72, Mood.UPBEAT, 130, null, List.of(5L, 7L));
        final Song song = Song.builder()
                .title("t").artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
        final ScoreBreakdown breakdown = new ScoreBreakdown(1.0, 0.9, 0.0, 1.0, 0.5, 0.7, 0.0);
        final RecommendationResult recommendationResult = new RecommendationResult(
                100L,
                List.of(new ScoredRecommendation(song, 0.8, "음역 적합", 1, breakdown)));

        final RecommendationHistoryResponse recommendationHistoryResponse = RecommendationHistoryResponse.from(
                recommendationRequestEntity, recommendationResult);

        // entity.id 는 영속 전이라 null. requestId 도 그대로 null.
        assertThat(recommendationHistoryResponse.requestId()).isNull();
        assertThat(recommendationHistoryResponse.voiceRangeLow()).isEqualTo(48);
        assertThat(recommendationHistoryResponse.voiceRangeHigh()).isEqualTo(72);
        assertThat(recommendationHistoryResponse.mood()).isEqualTo("UPBEAT");
        assertThat(recommendationHistoryResponse.preferredBpm()).isEqualTo(130);
        assertThat(recommendationHistoryResponse.requestedAt()).isNotNull();
        assertThat(recommendationHistoryResponse.recommendations()).hasSize(1);
        assertThat(recommendationHistoryResponse.recommendations().get(0).matchReason()).isEqualTo("음역 적합");
    }

    @Test
    @DisplayName("from: mood=null 일 때 mood 필드도 null 로 직렬화 (spec §5-2 — mood 옵션)")
    void from_nullMoodSerializesAsNull() {
        final RecommendationRequestEntity recommendationRequestEntity = RecommendationRequestEntity.create(
                "session-y", 48, 72, null, null, null, List.of());
        final RecommendationResult recommendationResult = new RecommendationResult(101L, List.of());

        final RecommendationHistoryResponse recommendationHistoryResponse = RecommendationHistoryResponse.from(
                recommendationRequestEntity, recommendationResult);

        assertThat(recommendationHistoryResponse.mood()).isNull();
        assertThat(recommendationHistoryResponse.preferredBpm()).isNull();
        assertThat(recommendationHistoryResponse.recommendations()).isEmpty();
    }

    @Test
    @DisplayName("from: breakdown 미보유 ScoredRecommendation(과거 추천 재조회) → 응답의 breakdown 도 null")
    void from_breakdownNull_propagatesNullToResponse() {
        final RecommendationRequestEntity recommendationRequestEntity = RecommendationRequestEntity.create(
                "session-z", 48, 72, Mood.CALM, null, null, List.of());
        final Song song = Song.builder()
                .title("t").artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
        final RecommendationResult recommendationResult = new RecommendationResult(
                102L,
                List.of(new ScoredRecommendation(song, 0.4, "전반적 매칭", 1)));

        final RecommendationHistoryResponse recommendationHistoryResponse = RecommendationHistoryResponse.from(
                recommendationRequestEntity, recommendationResult);

        assertThat(recommendationHistoryResponse.recommendations()).hasSize(1);
        assertThat(recommendationHistoryResponse.recommendations().get(0).breakdown()).isNull();
    }

    @Test
    @DisplayName("from: 결과의 추천 순서(rankPosition) 가 응답 리스트 순서로 그대로 보존")
    void from_preservesRecommendationOrder() {
        final RecommendationRequestEntity recommendationRequestEntity = RecommendationRequestEntity.create(
                "session-w", 48, 72, Mood.UPBEAT, 120, null, List.of());
        final Song s1 = Song.builder().title("first").artist("a")
                .keyOriginal(MusicalKey.C_MAJOR).metadataSource(MetadataSource.MANUAL_SEED).build();
        final Song s2 = Song.builder().title("second").artist("b")
                .keyOriginal(MusicalKey.C_MAJOR).metadataSource(MetadataSource.MANUAL_SEED).build();
        final RecommendationResult recommendationResult = new RecommendationResult(
                103L,
                List.of(
                        new ScoredRecommendation(s1, 0.9, "음역 적합", 1),
                        new ScoredRecommendation(s2, 0.7, "음역 적합", 2)));

        final RecommendationHistoryResponse recommendationHistoryResponse = RecommendationHistoryResponse.from(
                recommendationRequestEntity, recommendationResult);

        assertThat(recommendationHistoryResponse.recommendations())
                .extracting(recommendedSongResponse -> recommendedSongResponse.song().title())
                .containsExactly("first", "second");
        assertThat(recommendationHistoryResponse.recommendations())
                .extracting(RecommendedSongResponse::rankPosition)
                .containsExactly(1, 2);
    }
}
