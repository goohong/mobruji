package com.mobruji.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.recommendation.application.RecommendationScorer.Scored;
import com.mobruji.recommendation.application.RecommendationService.ScoredSong;
import com.mobruji.recommendation.domain.ScoreBreakdown;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.Mood;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;

/**
 * {@link DiversityPostProcessor} 경계값 회귀 가드.
 *
 * <p>기본 케이스는 {@link RecommendationDiversityTest} 에 있으며, 본 테스트는 다음 경계를 cover한다:
 * <ul>
 * <li>empty input / single result / resultCount=0 / resultCount &gt; N</li>
 * <li>maxSameArtist=1 — 모든 아티스트 1곡</li>
 * <li>genre null / 빈 문자열 — cap 면제</li>
 * <li>아티스트 cap 통과 + 장르 cap 차단 동시</li>
 * <li>점수 내림차순 정렬 보존</li>
 * </ul>
 */
class DiversityPostProcessorBoundaryTest {

    private static DiversityPostProcessor diversityPostProcessor(final int maxSameArtist, final int maxSameGenre) {
        final RecommendationProperties properties = new RecommendationProperties(
                new RecommendationProperties.Weights(0.5, 0.2, 0.2, 0.1, 0.1, 0.0, 0.0),
                new RecommendationProperties.Diversity(maxSameArtist, maxSameGenre),
                new RecommendationProperties.Tempo(40.0, Map.of(), 110),
                new RecommendationProperties.Generation(15.0, Map.of()), new RecommendationProperties.Gender(0.6, 0.5,
                        0.3),
                10,
                0.01,
                RecommendationProperties.SeedStrategy.DERIVED);
        return new DiversityPostProcessor(properties);
    }

    @Test
    @DisplayName("empty input → 빈 결과")
    void apply_emptyInput_returnsEmpty() {
        // given
        final List<ScoredSong> empty = List.of();

        // when
        final List<ScoredSong> result = diversityPostProcessor(2, 4).apply(empty, 5);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("single result + resultCount=1 → 그대로 1곡 반환")
    void apply_singleCandidate_returnsAsIs() {
        // given
        final List<ScoredSong> sorted = List.of(scored("only", "Solo", "팝", 0.9));

        // when
        final List<ScoredSong> result = diversityPostProcessor(2, 4).apply(sorted, 1);

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).song().getTitle()).isEqualTo("only");
    }

    @Test
    @DisplayName("resultCount=0 → 후보가 있어도 빈 결과")
    void apply_resultCountZero_returnsEmpty() {
        // given
        final List<ScoredSong> sorted = List.of(
                scored("a", "A1", "팝", 0.9),
                scored("b", "A2", "발라드", 0.85));

        // when
        final List<ScoredSong> result = diversityPostProcessor(2, 4).apply(sorted, 0);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("resultCount > 후보 수 → 후보 전체 반환 (점수 순 유지)")
    void apply_resultCountExceedsCandidates_returnsAll() {
        // given: 후보 3곡, resultCount=10
        final List<ScoredSong> sorted = List.of(
                scored("a", "A1", "팝", 0.9),
                scored("b", "A2", "발라드", 0.85),
                scored("c", "A3", "댄스", 0.8));

        // when
        final List<ScoredSong> result = diversityPostProcessor(2, 4).apply(sorted, 10);

        // then
        assertThat(result).hasSize(3);
        assertThat(result.stream().map(scoredSong -> scoredSong.song().getTitle()))
                .containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("maxSameArtist=1 → 같은 아티스트는 1곡만 선발 (다른 아티스트로 채움)")
    void apply_maxSameArtistOne_picksUniqueArtists() {
        // given: A 2곡 + B 2곡 + C 1곡, cap=1, resultCount=3 → A,B,C 각 1곡
        final List<ScoredSong> sorted = List.of(
                scored("a1", "A", "팝", 0.9),
                scored("a2", "A", "팝", 0.85),
                scored("b1", "B", "팝", 0.8),
                scored("b2", "B", "팝", 0.75),
                scored("c1", "C", "팝", 0.7));

        // when
        final List<ScoredSong> result = diversityPostProcessor(1, 10).apply(sorted, 3);

        // then
        assertThat(result.stream().map(scoredSong -> scoredSong.song().getArtist()))
                .containsExactly("A", "B", "C");
    }

    @Test
    @DisplayName("genre=null 인 후보는 장르 cap을 소진하지 않는다")
    void apply_nullGenre_doesNotConsumeGenreCap() {
        // given: 발라드 4곡 (cap 소진) + null장르 2곡 + 댄스 1곡, maxSameGenre=4, resultCount=6
        // → 발라드 4 + null 2 + 댄스 1 중 6곡 (null은 cap 면제, 그대로 통과)
        final List<ScoredSong> sorted = List.of(
                scored("b1", "A1", "발라드", 0.9),
                scored("b2", "A2", "발라드", 0.85),
                scored("b3", "A3", "발라드", 0.8),
                scored("b4", "A4", "발라드", 0.75),
                scored("n1", "A5", null, 0.7),
                scored("n2", "A6", null, 0.65),
                scored("d1", "A7", "댄스", 0.6));

        // when
        final List<ScoredSong> result = diversityPostProcessor(2, 4).apply(sorted, 6);

        // then: 6곡 모두 1차 통과 (null장르는 cap 미적용)
        assertThat(result).hasSize(6);
        assertThat(result.stream().map(scoredSong -> scoredSong.song().getTitle()))
                .containsExactly("b1", "b2", "b3", "b4", "n1", "n2");
    }

    @Test
    @DisplayName("genre=\"\" (빈 문자열) 인 후보도 장르 cap 면제")
    void apply_emptyGenre_doesNotConsumeGenreCap() {
        // given: 빈 문자열 장르 3곡 + 발라드 2곡, maxSameGenre=4, resultCount=5
        final List<ScoredSong> sorted = List.of(
                scored("e1", "A1", "", 0.9),
                scored("e2", "A2", "", 0.85),
                scored("e3", "A3", "", 0.8),
                scored("b1", "A4", "발라드", 0.75),
                scored("b2", "A5", "발라드", 0.7));

        // when
        final List<ScoredSong> result = diversityPostProcessor(2, 4).apply(sorted, 5);

        // then: 빈 장르 3 + 발라드 2 = 5
        assertThat(result).hasSize(5);
    }

    @Test
    @DisplayName("아티스트 cap이 장르 cap보다 먼저 차단 — 같은 후보가 두 cap 모두 위반해도 한 번만 skipped")
    void apply_artistCapBlocksFirst_beforeGenreCap() {
        // given: Artist A의 팝 3곡 + 다른 아티스트의 팝 5곡, maxSameArtist=2, maxSameGenre=4
        // → A 2곡 통과 (A3 skip - artist cap)
        // → B/C/D/E/F 중 팝 2곡까지 통과 (genre cap 도달 → 나머지 skip)
        // → resultCount=4 정확히 4곡
        final List<ScoredSong> sorted = List.of(
                scored("a1", "A", "팝", 0.95),
                scored("a2", "A", "팝", 0.90),
                scored("a3", "A", "팝", 0.85),
                scored("b1", "B", "팝", 0.80),
                scored("c1", "C", "팝", 0.75),
                scored("d1", "D", "팝", 0.70),
                scored("e1", "E", "팝", 0.65));

        // when
        final List<ScoredSong> result = diversityPostProcessor(2, 4).apply(sorted, 4);

        // then: A 2 + B + C = 4 (장르 cap 4 정확히 도달)
        assertThat(result).hasSize(4);
        assertThat(result.stream().map(scoredSong -> scoredSong.song().getTitle()))
                .containsExactly("a1", "a2", "b1", "c1");
    }

    @Test
    @DisplayName("결과는 입력 점수 내림차순을 그대로 유지한다")
    void apply_preservesScoreDescendingOrder() {
        // given: 서로 다른 아티스트·장르, 점수 내림차순
        final List<ScoredSong> sorted = List.of(
                scored("a", "A1", "팝", 0.99),
                scored("b", "A2", "락", 0.88),
                scored("c", "A3", "발라드", 0.77),
                scored("d", "A4", "댄스", 0.66));

        // when
        final List<ScoredSong> result = diversityPostProcessor(2, 4).apply(sorted, 4);

        // then
        assertThat(result.stream().map(scoredSong -> scoredSong.scored().total()))
                .containsExactly(0.99, 0.88, 0.77, 0.66);
    }

    @Test
    @DisplayName("fallback 후에도 결과 크기는 resultCount를 넘지 않는다")
    void apply_fallbackRespectsResultCount() {
        // given: Solo 10곡, cap=2, resultCount=3
        // → 1차 2곡 + fallback 1곡 = 정확히 3곡 (fallback이 끝까지 채우지 않음)
        final List<ScoredSong> sorted = List.of(
                scored("s1", "Solo", "팝", 0.99),
                scored("s2", "Solo", "팝", 0.95),
                scored("s3", "Solo", "팝", 0.90),
                scored("s4", "Solo", "팝", 0.85),
                scored("s5", "Solo", "팝", 0.80),
                scored("s6", "Solo", "팝", 0.75),
                scored("s7", "Solo", "팝", 0.70),
                scored("s8", "Solo", "팝", 0.65),
                scored("s9", "Solo", "팝", 0.60),
                scored("s10", "Solo", "팝", 0.55));

        // when
        final List<ScoredSong> result = diversityPostProcessor(2, 4).apply(sorted, 3);

        // then
        assertThat(result).hasSize(3);
        assertThat(result.stream().map(scoredSong -> scoredSong.song().getTitle()))
                .containsExactly("s1", "s2", "s3");
    }

    private static ScoredSong scored(final String title, final String artist, final String genre, final double score) {
        final Song song = Song.builder()
                .title(title).artist(artist)
                .keyOriginal(MusicalKey.C_MAJOR)
                .mood(Mood.UPBEAT)
                .genre(genre)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
        final ScoreBreakdown breakdown = new ScoreBreakdown(1.0, 1.0, 0.0, 1.0, 1.0, 0.5, 0.0, 0.0);
        return new ScoredSong(song, new Scored(score, breakdown));
    }
}
