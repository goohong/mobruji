package com.mobruji.recommendation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.Mood;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;

class ScoredRecommendationTest {

    private static Song sampleSong() {
        return Song.builder()
                .title("샘플")
                .artist("Artist")
                .keyOriginal(MusicalKey.C_MAJOR)
                .mood(Mood.UPBEAT)
                .genre("팝")
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
    }

    private static Song namedSong(final String title) {
        return Song.builder()
                .title(title)
                .artist("Artist")
                .keyOriginal(MusicalKey.C_MAJOR)
                .mood(Mood.UPBEAT)
                .genre("팝")
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
    }

    @Test
    @DisplayName("정상: 5인자 canonical 생성자는 breakdown까지 보존한다")
    void canonical_preservesBreakdown() {
        // given
        final Song song = sampleSong();
        final ScoreBreakdown breakdown = new ScoreBreakdown(0.9, 0.8, 0.7, 0.6, 0.5, 0.4);

        // when
        final ScoredRecommendation scored = new ScoredRecommendation(song, 0.75, "match", 3, breakdown);

        // then
        assertThat(scored.song()).isSameAs(song);
        assertThat(scored.score()).isEqualTo(0.75);
        assertThat(scored.matchReason()).isEqualTo("match");
        assertThat(scored.rankPosition()).isEqualTo(3);
        assertThat(scored.breakdown()).isSameAs(breakdown);
    }

    @Test
    @DisplayName("편의 생성자: breakdown 없는 4인자 호출이면 breakdown은 null")
    void convenience_breakdownNull() {
        // when
        final ScoredRecommendation scored = new ScoredRecommendation(sampleSong(), 0.5, "match", 1);

        // then
        assertThat(scored.breakdown()).isNull();
    }

    @Test
    @DisplayName("null guard: song이 null이면 NullPointerException")
    void create_nullSong_throws() {
        assertThatThrownBy(() -> new ScoredRecommendation(null, 0.5, "match", 1, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("song");
    }

    @Test
    @DisplayName("null guard: matchReason이 null이면 NullPointerException")
    void create_nullMatchReason_throws() {
        assertThatThrownBy(() -> new ScoredRecommendation(sampleSong(), 0.5, null, 1, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("matchReason");
    }

    @Test
    @DisplayName("invariant: rankPosition이 0 이하이면 IllegalArgumentException")
    void create_invalidRank_throws() {
        assertThatThrownBy(() -> new ScoredRecommendation(sampleSong(), 0.5, "match", 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rankPosition");
        assertThatThrownBy(() -> new ScoredRecommendation(sampleSong(), 0.5, "match", -1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rankPosition");
    }

    @Test
    @DisplayName("equality: 동일 필드를 가진 두 record 인스턴스는 equals & hashCode가 일치한다")
    void equals_hashCode_invariant() {
        // given
        final Song song = sampleSong();
        final ScoreBreakdown breakdown = new ScoreBreakdown(0.9, 0.8, 0.7, 0.6, 0.5, 0.4);
        final ScoredRecommendation left = new ScoredRecommendation(song, 0.75, "match", 3, breakdown);
        final ScoredRecommendation right = new ScoredRecommendation(song, 0.75, "match", 3, breakdown);

        // then
        assertThat(left).isEqualTo(right);
        assertThat(left.hashCode()).isEqualTo(right.hashCode());
    }

    @Test
    @DisplayName("equality: 단 하나의 필드만 달라도 equals가 false")
    void equals_differsOnAnyField() {
        // given
        final Song song = sampleSong();
        final ScoreBreakdown breakdown = new ScoreBreakdown(0.9, 0.8, 0.7, 0.6, 0.5, 0.4);
        final ScoredRecommendation base = new ScoredRecommendation(song, 0.75, "match", 3, breakdown);

        // then
        assertThat(base).isNotEqualTo(new ScoredRecommendation(song, 0.76, "match", 3, breakdown));
        assertThat(base).isNotEqualTo(new ScoredRecommendation(song, 0.75, "other", 3, breakdown));
        assertThat(base).isNotEqualTo(new ScoredRecommendation(song, 0.75, "match", 4, breakdown));
        assertThat(base).isNotEqualTo(new ScoredRecommendation(song, 0.75, "match", 3, null));
    }

    @Test
    @DisplayName("equality: breakdown null 두 인스턴스도 동일 필드면 equals true")
    void equals_breakdownNull_stillEqual() {
        // given
        final Song song = sampleSong();
        final ScoredRecommendation left = new ScoredRecommendation(song, 0.5, "match", 1);
        final ScoredRecommendation right = new ScoredRecommendation(song, 0.5, "match", 1);

        // then
        assertThat(left).isEqualTo(right);
        assertThat(left.hashCode()).isEqualTo(right.hashCode());
    }

    @Test
    @DisplayName("정렬 안정성: 동률 score는 List.sort 호출 후에도 원래 입력 순서를 보존한다")
    void sort_isStableOnTieScore() {
        // given: 동률 0.7 + 1개만 0.5. 입력 순서를 보존해야 stable sort 보장
        final ScoredRecommendation a = new ScoredRecommendation(namedSong("A"), 0.7, "tie", 1, null);
        final ScoredRecommendation b = new ScoredRecommendation(namedSong("B"), 0.7, "tie", 2, null);
        final ScoredRecommendation c = new ScoredRecommendation(namedSong("C"), 0.5, "low", 3, null);
        final ScoredRecommendation d = new ScoredRecommendation(namedSong("D"), 0.7, "tie", 4, null);

        // when: score 내림차순 정렬
        final List<ScoredRecommendation> list = new ArrayList<>(List.of(a, b, c, d));
        list.sort(Comparator.comparingDouble(ScoredRecommendation::score).reversed());

        // then: 동률 a/b/d는 입력 순서 유지, 마지막에 c
        assertThat(list).containsExactly(a, b, d, c);
    }

    @Test
    @DisplayName("정렬 결정성: 동일 입력을 여러 번 정렬해도 결과 순서가 변하지 않는다")
    void sort_isDeterministic() {
        // given
        final ScoredRecommendation a = new ScoredRecommendation(namedSong("A"), 0.9, "match", 1, null);
        final ScoredRecommendation b = new ScoredRecommendation(namedSong("B"), 0.9, "match", 2, null);
        final ScoredRecommendation c = new ScoredRecommendation(namedSong("C"), 0.8, "match", 3, null);
        final List<ScoredRecommendation> input = List.of(a, b, c);

        // when: 동일한 입력을 3회 정렬
        final List<ScoredRecommendation> first = new ArrayList<>(input);
        first.sort(Comparator.comparingDouble(ScoredRecommendation::score).reversed());
        final List<ScoredRecommendation> second = new ArrayList<>(input);
        second.sort(Comparator.comparingDouble(ScoredRecommendation::score).reversed());
        final List<ScoredRecommendation> third = new ArrayList<>(input);
        third.sort(Comparator.comparingDouble(ScoredRecommendation::score).reversed());

        // then
        assertThat(first).isEqualTo(second).isEqualTo(third);
    }
}
