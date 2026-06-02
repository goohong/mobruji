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
    @DisplayName("설명가능성(#1484): voiceFit은 breakdown.rangeFit을 그대로 노출하고 사유는 적합도 구간별 한국어")
    void voiceFit_derivesFromRangeFit() {
        // given: keyMatch=1.0(키 알려짐), rangeFit 구간별
        final Song song = sampleSong();
        final ScoredRecommendation high = new ScoredRecommendation(
                song, 0.9, "match", 1, new ScoreBreakdown(1.0, 0.82, 0.0, 0.0, 1.0, 0.5));
        final ScoredRecommendation mid = new ScoredRecommendation(
                song, 0.5, "match", 1, new ScoreBreakdown(1.0, 0.45, 0.0, 0.0, 1.0, 0.5));
        final ScoredRecommendation low = new ScoredRecommendation(
                song, 0.3, "match", 1, new ScoreBreakdown(1.0, 0.2, 0.0, 0.0, 1.0, 0.5));
        final ScoredRecommendation none = new ScoredRecommendation(
                song, 0.1, "match", 1, new ScoreBreakdown(1.0, 0.0, 0.0, 0.0, 1.0, 0.5));

        // then
        assertThat(high.voiceFit()).isEqualTo(0.82);
        assertThat(high.voiceFitReason()).isEqualTo("원곡 키가 음역대에 아주 잘 맞아요");
        assertThat(mid.voiceFitReason()).isEqualTo("원곡 키가 음역대에 무난하게 맞아요");
        assertThat(low.voiceFitReason()).isEqualTo("원곡 키가 음역대에 다소 부담될 수 있어요");
        assertThat(none.voiceFitReason()).isEqualTo("원곡 키가 음역대와 잘 맞지 않아요");
    }

    @Test
    @DisplayName("설명가능성(#1484): 곡 키 UNKNOWN(keyMatch<1.0)이면 적합도 산정 불가 사유로 노출")
    void voiceFit_unknownKey_reasonExplainsUncertainty() {
        // given: voiceRangeFit이 UNKNOWN 키에 부여하는 중립값(keyMatch=0.5, rangeFit=0.5)
        final ScoredRecommendation scored = new ScoredRecommendation(
                sampleSong(), 0.5, "match", 1, new ScoreBreakdown(0.5, 0.5, 0.0, 0.0, 1.0, 0.5));

        // then
        assertThat(scored.voiceFit()).isEqualTo(0.5);
        assertThat(scored.voiceFitReason()).isEqualTo("곡 키 정보가 없어 음역대 적합도를 정확히 알기 어려워요");
    }

    @Test
    @DisplayName("설명가능성(#1484): breakdown 없는 재조회 경로면 voiceFit/voiceFitReason 모두 null")
    void voiceFit_nullBreakdown_returnsNull() {
        // when
        final ScoredRecommendation scored = new ScoredRecommendation(sampleSong(), 0.5, "match", 1);

        // then
        assertThat(scored.voiceFit()).isNull();
        assertThat(scored.voiceFitReason()).isNull();
    }

    @Test
    @DisplayName("분위기 변별력(#1485): moodFit은 breakdown.moodMatch를 그대로 노출하고 사유는 유사도 구간별 한국어")
    void moodFit_derivesFromMoodMatch() {
        // given: moodMatch 구간별 (keyMatch/rangeFit 등 나머지 신호는 사유에 영향 없음)
        final Song song = sampleSong();
        final ScoredRecommendation exact = new ScoredRecommendation(
                song, 0.9, "match", 1, new ScoreBreakdown(1.0, 0.5, 0.0, 1.0, 1.0, 0.5));
        final ScoredRecommendation near = new ScoredRecommendation(
                song, 0.7, "match", 1, new ScoreBreakdown(1.0, 0.5, 0.0, 0.7, 1.0, 0.5));
        final ScoredRecommendation some = new ScoredRecommendation(
                song, 0.5, "match", 1, new ScoreBreakdown(1.0, 0.5, 0.0, 0.4, 1.0, 0.5));
        final ScoredRecommendation far = new ScoredRecommendation(
                song, 0.3, "match", 1, new ScoreBreakdown(1.0, 0.5, 0.0, 0.2, 1.0, 0.5));

        // then
        assertThat(exact.moodFit()).isEqualTo(1.0);
        assertThat(exact.moodFitReason()).isEqualTo("요청하신 분위기와 딱 맞아요");
        assertThat(near.moodFitReason()).isEqualTo("요청하신 분위기와 잘 어울려요");
        assertThat(some.moodFitReason()).isEqualTo("요청하신 분위기와 어느 정도 비슷해요");
        assertThat(far.moodFitReason()).isEqualTo("요청하신 분위기와는 결이 조금 달라요");
    }

    @Test
    @DisplayName("분위기 변별력(#1485): 분위기 신호 0.0(미입력·곡 mood 부재)이면 moodFitReason은 null")
    void moodFit_zeroSignal_reasonNull() {
        // given: moodMatch=0.0 (분위기 미입력 또는 곡 mood 부재)
        final ScoredRecommendation scored = new ScoredRecommendation(
                sampleSong(), 0.5, "match", 1, new ScoreBreakdown(1.0, 0.5, 0.0, 0.0, 1.0, 0.5));

        // then: 점수는 0.0 으로 노출하되 설명할 근거가 없으므로 사유는 null
        assertThat(scored.moodFit()).isEqualTo(0.0);
        assertThat(scored.moodFitReason()).isNull();
    }

    @Test
    @DisplayName("분위기 변별력(#1485): breakdown 없는 재조회 경로면 moodFit/moodFitReason 모두 null")
    void moodFit_nullBreakdown_returnsNull() {
        // when
        final ScoredRecommendation scored = new ScoredRecommendation(sampleSong(), 0.5, "match", 1);

        // then
        assertThat(scored.moodFit()).isNull();
        assertThat(scored.moodFitReason()).isNull();
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
