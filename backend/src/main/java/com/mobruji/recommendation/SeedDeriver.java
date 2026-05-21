package com.mobruji.recommendation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

import com.mobruji.song.Mood;

/**
 * 추천 요청 식별자로부터 결정적 seed(long)를 도출하는 유틸.
 *
 * <p>spec: {@code docs/features/recommendation-algorithm-v1.md} §3 비기능 — 결정성
 * (같은 요청 입력 → 같은 결과). {@link RecommendationScorer}의 jitter용 {@link java.util.Random}에
 * 주입되어 호출간 결과 흔들림을 제거한다.
 *
 * <p>알고리즘: 입력 필드를 정규화 직렬화 → SHA-256 해시 → 상위 8바이트를 big-endian long으로 변환.
 * SHA-256은 JDK 표준 제공이라 외부 의존성이 없고, 입력 비트 변화에 출력이 골고루 흩어진다.
 */
public final class SeedDeriver {

    private static final String HASH_ALGORITHM = "SHA-256";
    private static final String FIELD_SEPARATOR = "|";

    private SeedDeriver() {
    }

    /**
     * 추천 요청 파라미터로부터 결정적 seed를 도출한다.
     *
     * @param sessionId      세션 식별자(필수, blank 불가).
     * @param voiceRangeLow  사용자 음역대 하한(MIDI).
     * @param voiceRangeHigh 사용자 음역대 상한(MIDI).
     * @param mood           요청 분위기(nullable).
     * @return 같은 입력에 대해 항상 같은 long.
     */
    public static long derive(
            final String sessionId,
            final int voiceRangeLow,
            final int voiceRangeHigh,
            final Mood mood) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        final String canonical = canonicalize(sessionId, voiceRangeLow, voiceRangeHigh, mood);
        final byte[] digest = sha256(canonical);
        return toLongBigEndian(digest);
    }

    private static String canonicalize(
            final String sessionId,
            final int voiceRangeLow,
            final int voiceRangeHigh,
            final Mood mood) {
        final String moodToken = mood == null ? "" : mood.name();
        return sessionId
                + FIELD_SEPARATOR + voiceRangeLow
                + FIELD_SEPARATOR + voiceRangeHigh
                + FIELD_SEPARATOR + moodToken;
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
