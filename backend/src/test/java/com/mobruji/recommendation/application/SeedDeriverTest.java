package com.mobruji.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.song.domain.Mood;

/**
 * {@link SeedDeriver} 단위 테스트.
 *
 * <p>spec: {@code docs/features/recommendation-algorithm-v1.md} §3 비기능 — 결정성.
 *
 * <p>본 테스트는 결정성(동일 입력 → 동일 seed)과 entropy 보존(다른 입력 → 다른 seed)을
 * 모든 입력 필드에 대해 검증한다. excludeSongIds 입력 포함은 rev 사이클 3 누적 경고에
 * 대응하는 회귀 가드(재추천 변주 보존).
 *
 * <p>v2(#218)에서 preferredBpm 입력이 seed에 포함되어, 같은 voiceRange/sessionId라도
 * 사용자 선호 BPM이 다르면 다른 결과를 보장한다.
 *
 * <p>#299: entropy 단정의 단일 진실 레이어를 본 단위 테스트로 둔다 (RestAssured E2E
 * {@code RecommendationDeterminismTest}는 결정성 회귀만 책임진다). 곡 시드/가중치
 * 변경에 flaky 했던 E2E entropy 단정이 본 레이어로 이동했다.
 */
class SeedDeriverTest {

    @Test
    @DisplayName("같은 입력은 항상 같은 seed (결정성)")
    void derive_sameInputs_returnsSameSeed() {
        // given
        final String sessionId = "session-abc";
        final int voiceRangeLow = 55;
        final int voiceRangeHigh = 75;
        final Mood mood = Mood.UPBEAT;
        final Integer preferredBpm = 120;
        final List<Long> excludeSongIds = List.of(10L, 20L);
        // when
        final long first = SeedDeriver.derive(
                sessionId, voiceRangeLow, voiceRangeHigh, mood, preferredBpm, excludeSongIds);
        final long second = SeedDeriver.derive(
                sessionId, voiceRangeLow, voiceRangeHigh, mood, preferredBpm, excludeSongIds);
        // then
        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("다른 sessionId는 다른 seed (entropy 보존)")
    void derive_differentSession_returnsDifferentSeed() {
        // when
        final long a = SeedDeriver.derive("session-a", 55, 75, Mood.UPBEAT, null, List.of());
        final long b = SeedDeriver.derive("session-b", 55, 75, Mood.UPBEAT, null, List.of());
        // then
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("다른 음역대는 다른 seed")
    void derive_differentVoiceRange_returnsDifferentSeed() {
        // when
        final long a = SeedDeriver.derive("s", 55, 75, Mood.UPBEAT, null, List.of());
        final long b = SeedDeriver.derive("s", 56, 75, Mood.UPBEAT, null, List.of());
        // then
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("다른 mood는 다른 seed (null vs 값 포함)")
    void derive_differentMood_returnsDifferentSeed() {
        // when
        final long upbeat = SeedDeriver.derive("s", 55, 75, Mood.UPBEAT, null, List.of());
        final long calm = SeedDeriver.derive("s", 55, 75, Mood.CALM, null, List.of());
        final long none = SeedDeriver.derive("s", 55, 75, null, null, List.of());
        // then
        assertThat(upbeat).isNotEqualTo(calm);
        assertThat(upbeat).isNotEqualTo(none);
        assertThat(calm).isNotEqualTo(none);
    }

    @Test
    @DisplayName("mood=null도 일관된 seed (호출마다 흔들리지 않음)")
    void derive_nullMood_isStable() {
        // when
        final long first = SeedDeriver.derive("s", 55, 75, null, null, List.of());
        final long second = SeedDeriver.derive("s", 55, 75, null, null, List.of());
        // then
        assertThat(first).isEqualTo(second);
    }

    /**
     * rev 사이클 3 누적 패턴 회귀 가드.
     * 같은 voiceRange/sessionId라도 excludeSongIds가 다르면 seed가 달라야
     * "다시 버튼이 같은 결과 반환" 회귀를 막을 수 있다.
     */
    @Test
    @DisplayName("excludeSongIds가 다르면 다른 seed (재추천 변주 보존)")
    void derive_differentExcludeSongIds_returnsDifferentSeed() {
        // given
        final String sessionId = "same-session";
        // when
        final long emptyExclude = SeedDeriver.derive(sessionId, 55, 75, Mood.UPBEAT, null, List.of());
        final long oneExclude = SeedDeriver.derive(sessionId, 55, 75, Mood.UPBEAT, null, List.of(10L));
        final long twoExclude = SeedDeriver.derive(sessionId, 55, 75, Mood.UPBEAT, null, List.of(10L, 20L));
        // then
        assertThat(emptyExclude).isNotEqualTo(oneExclude);
        assertThat(oneExclude).isNotEqualTo(twoExclude);
        assertThat(emptyExclude).isNotEqualTo(twoExclude);
    }

    /**
     * excludeSongIds는 정렬·중복 제거 후 직렬화되므로, 호출 측 순서·중복이 달라도 의미상 같은 셋이면 같은 seed.
     * 결정성 보장의 또 다른 측면.
     */
    @Test
    @DisplayName("excludeSongIds 순서/중복 차이는 같은 seed (정규화)")
    void derive_excludeSongIds_orderAndDuplicates_normalized() {
        // when
        final long ascending = SeedDeriver.derive("s", 55, 75, Mood.UPBEAT, null, List.of(10L, 20L, 30L));
        final long descending = SeedDeriver.derive("s", 55, 75, Mood.UPBEAT, null, List.of(30L, 20L, 10L));
        final long withDuplicate = SeedDeriver.derive("s", 55, 75, Mood.UPBEAT, null, List.of(20L, 10L, 30L, 10L));
        // then
        assertThat(ascending).isEqualTo(descending);
        assertThat(ascending).isEqualTo(withDuplicate);
    }

    /**
     * null 리스트와 빈 리스트는 의미상 동일 ("제외 없음") → 같은 seed.
     */
    @Test
    @DisplayName("excludeSongIds null과 빈 리스트는 같은 seed (의미 동등)")
    void derive_excludeSongIds_nullEqualsEmpty() {
        // when
        final long nullList = SeedDeriver.derive("s", 55, 75, Mood.UPBEAT, null, null);
        final long emptyList = SeedDeriver.derive("s", 55, 75, Mood.UPBEAT, null, List.of());
        // then
        assertThat(nullList).isEqualTo(emptyList);
    }

    /**
     * v2(#218) 회귀 가드: 같은 음역/세션이라도 preferredBpm이 다르면 다른 seed.
     * 사용자 선호 BPM 입력이 결과 변주에 영향을 주어야 한다.
     */
    @Test
    @DisplayName("preferredBpm이 다르면 다른 seed (v2 #218)")
    void derive_differentPreferredBpm_returnsDifferentSeed() {
        // when
        final long bpm120 = SeedDeriver.derive("s", 55, 75, Mood.UPBEAT, 120, List.of());
        final long bpm140 = SeedDeriver.derive("s", 55, 75, Mood.UPBEAT, 140, List.of());
        final long bpmNull = SeedDeriver.derive("s", 55, 75, Mood.UPBEAT, null, List.of());
        // then
        assertThat(bpm120).isNotEqualTo(bpm140);
        assertThat(bpm120).isNotEqualTo(bpmNull);
        assertThat(bpm140).isNotEqualTo(bpmNull);
    }

    @Test
    @DisplayName("같은 preferredBpm은 같은 seed (v2 결정성)")
    void derive_samePreferredBpm_isStable() {
        // when
        final long first = SeedDeriver.derive("s", 55, 75, Mood.UPBEAT, 120, List.of());
        final long second = SeedDeriver.derive("s", 55, 75, Mood.UPBEAT, 120, List.of());
        // then
        assertThat(first).isEqualTo(second);
    }

    /**
     * 결정성 로그({@code event=recommendation.created request.input.hash=...}) 용 hash 가드.
     *
     * <p>spec: {@code docs/features/recommendation-algorithm-v1.md} §3 비기능 (d).
     * 같은 입력 → 같은 hash, 다른 입력 → 다른 hash, 길이는 항상 16 lowercase hex.
     */
    @Test
    @DisplayName("hashHex16: 같은 입력은 같은 16자 hex hash")
    void hashHex16_sameInput_returnsSameHash() {
        // when
        final String first = SeedDeriver.hashHex16("s", 55, 75, Mood.UPBEAT, 120, List.of(10L));
        final String second = SeedDeriver.hashHex16("s", 55, 75, Mood.UPBEAT, 120, List.of(10L));
        // then
        assertThat(first).isEqualTo(second).hasSize(16).matches("[0-9a-f]{16}");
    }

    @Test
    @DisplayName("hashHex16: 다른 입력은 다른 hash (sessionId/voiceRange/mood/bpm/exclude 모두)")
    void hashHex16_differentInputs_returnDifferentHashes() {
        // given
        final String base = SeedDeriver.hashHex16("s", 55, 75, Mood.UPBEAT, 120, List.of());
        // when
        final String diffSession = SeedDeriver.hashHex16("t", 55, 75, Mood.UPBEAT, 120, List.of());
        final String diffVoice = SeedDeriver.hashHex16("s", 56, 75, Mood.UPBEAT, 120, List.of());
        final String diffMood = SeedDeriver.hashHex16("s", 55, 75, Mood.CALM, 120, List.of());
        final String diffBpm = SeedDeriver.hashHex16("s", 55, 75, Mood.UPBEAT, 140, List.of());
        final String diffExclude = SeedDeriver.hashHex16("s", 55, 75, Mood.UPBEAT, 120, List.of(1L));
        // then
        assertThat(base)
                .isNotEqualTo(diffSession)
                .isNotEqualTo(diffVoice)
                .isNotEqualTo(diffMood)
                .isNotEqualTo(diffBpm)
                .isNotEqualTo(diffExclude);
    }
}
