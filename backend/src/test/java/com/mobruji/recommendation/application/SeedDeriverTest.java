package com.mobruji.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.recommendation.domain.AgeGroup;
import com.mobruji.song.domain.Mood;
import com.mobruji.song.domain.VocalGender;

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
                sessionId, voiceRangeLow, voiceRangeHigh, mood, preferredBpm, null, null, excludeSongIds);
        final long second = SeedDeriver.derive(
                sessionId, voiceRangeLow, voiceRangeHigh, mood, preferredBpm, null, null, excludeSongIds);
        // then
        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("다른 sessionId는 다른 seed (entropy 보존)")
    void derive_differentSession_returnsDifferentSeed() {
        // when
        final long a = SeedDeriver.derive("session-a", 55, 75, Mood.UPBEAT, null, null, null, List.of());
        final long b = SeedDeriver.derive("session-b", 55, 75, Mood.UPBEAT, null, null, null, List.of());
        // then
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("다른 음역대는 다른 seed")
    void derive_differentVoiceRange_returnsDifferentSeed() {
        // when
        final long a = SeedDeriver.derive("s", 55, 75, Mood.UPBEAT, null, null, null, List.of());
        final long b = SeedDeriver.derive("s", 56, 75, Mood.UPBEAT, null, null, null, List.of());
        // then
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("다른 mood는 다른 seed (null vs 값 포함)")
    void derive_differentMood_returnsDifferentSeed() {
        // when
        final long upbeat = SeedDeriver.derive("s", 55, 75, Mood.UPBEAT, null, null, null, List.of());
        final long calm = SeedDeriver.derive("s", 55, 75, Mood.CALM, null, null, null, List.of());
        final long none = SeedDeriver.derive("s", 55, 75, null, null, null, null, List.of());
        // then
        assertThat(upbeat).isNotEqualTo(calm);
        assertThat(upbeat).isNotEqualTo(none);
        assertThat(calm).isNotEqualTo(none);
    }

    @Test
    @DisplayName("mood=null도 일관된 seed (호출마다 흔들리지 않음)")
    void derive_nullMood_isStable() {
        // when
        final long first = SeedDeriver.derive("s", 55, 75, null, null, null, null, List.of());
        final long second = SeedDeriver.derive("s", 55, 75, null, null, null, null, List.of());
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
        final long emptyExclude = SeedDeriver.derive(sessionId, 55, 75, Mood.UPBEAT, null, null, null, List.of());
        final long oneExclude = SeedDeriver.derive(sessionId, 55, 75, Mood.UPBEAT, null, null, null, List.of(10L));
        final long twoExclude = SeedDeriver.derive(sessionId, 55, 75, Mood.UPBEAT, null, null, null, List.of(10L, 20L));
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
        final long ascending = SeedDeriver.derive("s", 55, 75, Mood.UPBEAT, null, null, null, List.of(10L, 20L, 30L));
        final long descending = SeedDeriver.derive("s", 55, 75, Mood.UPBEAT, null, null, null, List.of(30L, 20L, 10L));
        final long withDuplicate = SeedDeriver.derive("s", 55, 75, Mood.UPBEAT, null, null, null, List.of(20L, 10L, 30L,
                10L));
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
        final long nullList = SeedDeriver.derive("s", 55, 75, Mood.UPBEAT, null, null, null, null);
        final long emptyList = SeedDeriver.derive("s", 55, 75, Mood.UPBEAT, null, null, null, List.of());
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
        final long bpm120 = SeedDeriver.derive("s", 55, 75, Mood.UPBEAT, 120, null, null, List.of());
        final long bpm140 = SeedDeriver.derive("s", 55, 75, Mood.UPBEAT, 140, null, null, List.of());
        final long bpmNull = SeedDeriver.derive("s", 55, 75, Mood.UPBEAT, null, null, null, List.of());
        // then
        assertThat(bpm120).isNotEqualTo(bpm140);
        assertThat(bpm120).isNotEqualTo(bpmNull);
        assertThat(bpm140).isNotEqualTo(bpmNull);
    }

    /**
     * #1487 회귀 가드: 같은 음역/세션이라도 ageGroup이 다르면 다른 seed.
     * 연령대 입력이 결과 변주에 영향을 주어야 한다.
     */
    @Test
    @DisplayName("ageGroup이 다르면 다른 seed (#1487)")
    void derive_differentAgeGroup_returnsDifferentSeed() {
        // when
        final long twenties = SeedDeriver.derive("s", 55, 75, Mood.UPBEAT, null, AgeGroup.TWENTIES, null, List.of());
        final long forties = SeedDeriver.derive("s", 55, 75, Mood.UPBEAT, null, AgeGroup.FORTIES, null, List.of());
        final long none = SeedDeriver.derive("s", 55, 75, Mood.UPBEAT, null, null, null, List.of());
        // then
        assertThat(twenties).isNotEqualTo(forties);
        assertThat(twenties).isNotEqualTo(none);
        assertThat(forties).isNotEqualTo(none);
    }

    @Test
    @DisplayName("같은 ageGroup은 같은 seed (#1487 결정성)")
    void derive_sameAgeGroup_isStable() {
        // when
        final long first = SeedDeriver.derive("s", 55, 75, Mood.UPBEAT, null, AgeGroup.THIRTIES, null, List.of());
        final long second = SeedDeriver.derive("s", 55, 75, Mood.UPBEAT, null, AgeGroup.THIRTIES, null, List.of());
        // then
        assertThat(first).isEqualTo(second);
    }

    /**
     * #1767 회귀 가드: 같은 음역/세션이라도 gender 필터가 다르면 다른 seed.
     * 성별 필터 입력이 결과 변주에 영향을 주어야 한다(같은 음역인데 남자곡/여자곡 토글이 무시되는 회귀 방지).
     */
    @Test
    @DisplayName("gender가 다르면 다른 seed (#1767)")
    void derive_differentGender_returnsDifferentSeed() {
        // when
        final long male = SeedDeriver.derive("s", 55, 75, Mood.UPBEAT, null, null, VocalGender.MALE, List.of());
        final long female = SeedDeriver.derive("s", 55, 75, Mood.UPBEAT, null, null, VocalGender.FEMALE, List.of());
        final long none = SeedDeriver.derive("s", 55, 75, Mood.UPBEAT, null, null, null, List.of());
        // then
        assertThat(male).isNotEqualTo(female);
        assertThat(male).isNotEqualTo(none);
        assertThat(female).isNotEqualTo(none);
    }

    @Test
    @DisplayName("같은 gender는 같은 seed (#1767 결정성)")
    void derive_sameGender_isStable() {
        // when
        final long first = SeedDeriver.derive("s", 55, 75, Mood.UPBEAT, null, null, VocalGender.FEMALE, List.of());
        final long second = SeedDeriver.derive("s", 55, 75, Mood.UPBEAT, null, null, VocalGender.FEMALE, List.of());
        // then
        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("같은 preferredBpm은 같은 seed (v2 결정성)")
    void derive_samePreferredBpm_isStable() {
        // when
        final long first = SeedDeriver.derive("s", 55, 75, Mood.UPBEAT, 120, null, null, List.of());
        final long second = SeedDeriver.derive("s", 55, 75, Mood.UPBEAT, 120, null, null, List.of());
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
        final String first = SeedDeriver.hashHex16("s", 55, 75, Mood.UPBEAT, 120, null, null, List.of(10L));
        final String second = SeedDeriver.hashHex16("s", 55, 75, Mood.UPBEAT, 120, null, null, List.of(10L));
        // then
        assertThat(first).isEqualTo(second).hasSize(16).matches("[0-9a-f]{16}");
    }

    /**
     * NPE 가드 회귀: sessionId가 null이면 즉시 {@link NullPointerException}.
     * 결정성 hash/seed 도출 직전에 fail-fast해야 호출 측 로그에 의미 없는 hash가 남지 않는다.
     */
    @Test
    @DisplayName("derive: sessionId=null → NullPointerException (fail-fast)")
    void derive_nullSessionId_throwsNpe() {
        // when / then
        assertThatNullPointerException()
                .isThrownBy(() -> SeedDeriver.derive(null, 55, 75, Mood.UPBEAT, 120, null, null, List.of()))
                .withMessageContaining("sessionId");
    }

    @Test
    @DisplayName("hashHex16: sessionId=null → NullPointerException (fail-fast)")
    void hashHex16_nullSessionId_throwsNpe() {
        // when / then
        assertThatNullPointerException()
                .isThrownBy(() -> SeedDeriver.hashHex16(null, 55, 75, Mood.UPBEAT, 120, null, null, List.of()))
                .withMessageContaining("sessionId");
    }

    /**
     * excludeSongIds 리스트에 null element가 섞이면 {@code removeIf(Objects::isNull)} 분기로
     * 제거되어, 의미상 같은 셋(null 빠진 셋)과 같은 seed가 산출되어야 한다. 호출 측이
     * 임의 컬렉션을 그대로 넘겨도 결정성이 유지된다는 보장.
     */
    @Test
    @DisplayName("derive: excludeSongIds에 null element 섞여도 제거 후 정상 seed")
    void derive_excludeSongIds_containsNull_isFilteredOut() {
        // given: null 포함 vs null 빠진 동등 셋 (Arrays.asList는 null 허용)
        final List<Long> withNull = Arrays.asList(10L, null, 20L);
        final List<Long> withoutNull = List.of(10L, 20L);
        // when
        final long seedWithNull = SeedDeriver.derive("s", 55, 75, Mood.UPBEAT, 120, null, null, withNull);
        final long seedWithoutNull = SeedDeriver.derive("s", 55, 75, Mood.UPBEAT, 120, null, null, withoutNull);
        // then
        assertThat(seedWithNull).isEqualTo(seedWithoutNull);
    }

    /**
     * hashHex16도 excludeSongIds 정규화(정렬·distinct)를 적용해야 결정성 로그가 의미를 가진다.
     * 호출 측 순서·중복이 달라도 의미상 같은 셋이면 같은 hash. derive와 같은 canonicalize를 공유하는지
     * 회귀 가드.
     */
    @Test
    @DisplayName("hashHex16: excludeSongIds 순서/중복 차이는 같은 hash (정규화)")
    void hashHex16_excludeSongIds_orderAndDuplicates_normalized() {
        // when
        final String ascending = SeedDeriver.hashHex16("s", 55, 75, Mood.UPBEAT, 120, null, null, List.of(10L, 20L,
                30L));
        final String descending = SeedDeriver.hashHex16("s", 55, 75, Mood.UPBEAT, 120, null, null, List.of(30L, 20L,
                10L));
        final String withDuplicate = SeedDeriver.hashHex16("s", 55, 75, Mood.UPBEAT, 120, null, null, List.of(20L, 10L,
                30L,
                10L));
        // then
        assertThat(ascending).isEqualTo(descending).isEqualTo(withDuplicate);
    }

    /**
     * null 리스트와 빈 리스트는 의미상 동일("제외 없음") → hashHex16도 같은 hash.
     * derive 쪽 보장이 hashHex16에도 적용되는지 회귀 가드.
     */
    @Test
    @DisplayName("hashHex16: excludeSongIds null과 빈 리스트는 같은 hash (의미 동등)")
    void hashHex16_excludeSongIds_nullEqualsEmpty() {
        // when
        final String nullList = SeedDeriver.hashHex16("s", 55, 75, Mood.UPBEAT, 120, null, null, null);
        final String emptyList = SeedDeriver.hashHex16("s", 55, 75, Mood.UPBEAT, 120, null, null, List.of());
        // then
        assertThat(nullList).isEqualTo(emptyList);
    }

    @Test
    @DisplayName("hashHex16: 다른 입력은 다른 hash (sessionId/voiceRange/mood/bpm/exclude 모두)")
    void hashHex16_differentInputs_returnDifferentHashes() {
        // given
        final String base = SeedDeriver.hashHex16("s", 55, 75, Mood.UPBEAT, 120, null, null, List.of());
        // when
        final String diffSession = SeedDeriver.hashHex16("t", 55, 75, Mood.UPBEAT, 120, null, null, List.of());
        final String diffVoice = SeedDeriver.hashHex16("s", 56, 75, Mood.UPBEAT, 120, null, null, List.of());
        final String diffMood = SeedDeriver.hashHex16("s", 55, 75, Mood.CALM, 120, null, null, List.of());
        final String diffBpm = SeedDeriver.hashHex16("s", 55, 75, Mood.UPBEAT, 140, null, null, List.of());
        final String diffExclude = SeedDeriver.hashHex16("s", 55, 75, Mood.UPBEAT, 120, null, null, List.of(1L));
        // then
        assertThat(base)
                .isNotEqualTo(diffSession)
                .isNotEqualTo(diffVoice)
                .isNotEqualTo(diffMood)
                .isNotEqualTo(diffBpm)
                .isNotEqualTo(diffExclude);
    }
}
