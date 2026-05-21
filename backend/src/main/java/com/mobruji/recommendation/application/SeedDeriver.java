package com.mobruji.recommendation.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import com.mobruji.song.domain.Mood;

/**
 * 추천 요청 식별자로부터 결정적 seed(long)를 도출하는 유틸.
 *
 * <p>spec: {@code docs/features/recommendation-algorithm-v1.md} §3 비기능 — 결정성
 * (같은 요청 입력 → 같은 결과). {@link RecommendationScorer}의 jitter용 {@link java.util.Random}에
 * 주입되어 호출간 결과 흔들림을 제거한다.
 *
 * <p>알고리즘: 입력 필드를 정규화 직렬화 → SHA-256 해시 → 상위 8바이트를 big-endian long으로 변환.
 * SHA-256은 JDK 표준 제공이라 외부 의존성이 없고, 입력 비트 변화에 출력이 골고루 흩어진다.
 *
 * <p>누적 패턴: 추천 결정성에 영향을 주는 모든 요청 입력은 seed에 포함되어야 한다.
 * "재추천" 흐름의 {@code excludeSongIds}도 입력에 포함시켜, 같은 voiceRange라도 제외 곡 셋이
 * 달라지면 다른 seed → 다른 jitter → 다른 결과를 보장한다 (rev 사이클 3 누적 경고: "다시 버튼이
 * 같은 결과 반환" 회귀 방지).
 */
public final class SeedDeriver {

    private static final String HASH_ALGORITHM = "SHA-256";
    private static final String FIELD_SEPARATOR = "|";
    private static final String LIST_DELIMITER = ",";

    private SeedDeriver() {
    }

    /**
     * 추천 요청 파라미터로부터 결정적 seed를 도출한다.
     *
     * @param sessionId      세션 식별자(필수, blank 불가).
     * @param voiceRangeLow  사용자 음역대 하한(MIDI).
     * @param voiceRangeHigh 사용자 음역대 상한(MIDI).
     * @param mood           요청 분위기(nullable).
     * @param preferredBpm   사용자 선호 BPM(nullable, v2 #218 입력). null과 정수 입력은 다른 seed.
     * @param excludeSongIds 결과에서 제외할 곡 ID 목록(nullable → 빈 리스트로 처리).
     *                       내부에서 정렬·중복 제거 후 직렬화하므로 호출 측 순서 무관.
     * @return 같은 입력에 대해 항상 같은 long.
     */
    public static long derive(
            final String sessionId,
            final int voiceRangeLow,
            final int voiceRangeHigh,
            final Mood mood,
            final Integer preferredBpm,
            final List<Long> excludeSongIds) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        final String canonical = canonicalize(
                sessionId, voiceRangeLow, voiceRangeHigh, mood, preferredBpm, excludeSongIds);
        final byte[] digest = sha256(canonical);
        return toLongBigEndian(digest);
    }

    private static String canonicalize(
            final String sessionId,
            final int voiceRangeLow,
            final int voiceRangeHigh,
            final Mood mood,
            final Integer preferredBpm,
            final List<Long> excludeSongIds) {
        final String moodToken = mood == null ? "" : mood.name();
        final String bpmToken = preferredBpm == null ? "" : preferredBpm.toString();
        final String excludeToken = normalizeExcludeIds(excludeSongIds);
        return sessionId
                + FIELD_SEPARATOR + voiceRangeLow
                + FIELD_SEPARATOR + voiceRangeHigh
                + FIELD_SEPARATOR + moodToken
                + FIELD_SEPARATOR + bpmToken
                + FIELD_SEPARATOR + excludeToken;
    }

    /**
     * excludeSongIds를 결정적으로 직렬화한다. null·중복·순서 차이가 같은 의미인 두 입력에 대해
     * 같은 토큰을 반환해야 한다 — 정렬 + distinct.
     */
    private static String normalizeExcludeIds(final List<Long> excludeSongIds) {
        if (excludeSongIds == null || excludeSongIds.isEmpty()) {
            return "";
        }
        final List<Long> sorted = new ArrayList<>(excludeSongIds);
        sorted.removeIf(Objects::isNull);
        Collections.sort(sorted);
        return sorted.stream().distinct().map(String::valueOf).collect(Collectors.joining(LIST_DELIMITER));
    }

    private static byte[] sha256(final String input) {
        try {
            final MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            return digest.digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable on this JVM", e);
        }
    }

    private static long toLongBigEndian(final byte[] digest) {
        long seed = 0L;
        for (int i = 0; i < Long.BYTES; i++) {
            seed = (seed << 8) | (digest[i] & 0xFFL);
        }
        return seed;
    }
}
