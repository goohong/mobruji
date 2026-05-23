package com.mobruji.recommendation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.Mood;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;

class RecommendationResultTest {

    private static ScoredRecommendation sampleRecommendation(final int rankPosition) {
        final Song song = Song.builder()
                .title("샘플-" + rankPosition)
                .artist("Artist")
                .keyOriginal(MusicalKey.C_MAJOR)
                .mood(Mood.UPBEAT)
                .genre("팝")
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
        return new ScoredRecommendation(song, 0.5, "match", rankPosition);
    }

    @Test
    @DisplayName("정상: requestId + recommendations 리스트로 생성된다")
    void create_valid() {
        // given
        final List<ScoredRecommendation> recommendations = List.of(sampleRecommendation(1), sampleRecommendation(2));

        // when
        final RecommendationResult result = new RecommendationResult(42L, recommendations);

        // then
        assertThat(result.requestId()).isEqualTo(42L);
        assertThat(result.recommendations()).hasSize(2);
    }

    @Test
    @DisplayName("null guard: requestId가 null이면 NullPointerException")
    void create_nullRequestId_throws() {
        assertThatThrownBy(() -> new RecommendationResult(null, List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("requestId");
    }

    @Test
    @DisplayName("null guard: recommendations가 null이면 NullPointerException")
    void create_nullRecommendations_throws() {
        assertThatThrownBy(() -> new RecommendationResult(1L, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("recommendations");
    }

    @Test
    @DisplayName("불변 사본: 생성 시 List.copyOf로 감싸 외부 변경이 내부에 새지 않는다")
    void create_defensiveCopy() {
        // given: 가변 리스트로 시작
        final List<ScoredRecommendation> mutable = new ArrayList<>();
        mutable.add(sampleRecommendation(1));
        final RecommendationResult result = new RecommendationResult(1L, mutable);

        // when: 원본 리스트를 변형
        mutable.add(sampleRecommendation(2));

        // then: 내부 리스트는 영향받지 않는다
        assertThat(result.recommendations()).hasSize(1);
    }

    @Test
    @DisplayName("불변 사본: 반환된 리스트는 add/remove에 대해 UnsupportedOperationException")
    void recommendations_immutable() {
        final RecommendationResult result = new RecommendationResult(1L, List.of(sampleRecommendation(1)));

        assertThatThrownBy(() -> result.recommendations().add(sampleRecommendation(2)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
