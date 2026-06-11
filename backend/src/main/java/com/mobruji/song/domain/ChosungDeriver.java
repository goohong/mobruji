package com.mobruji.song.domain;

/**
 * 한글 음절을 초성(lead consonant)열로 파생한다 — {@code "발라드" → "ㅂㄹㄷ"}.
 *
 * <p>곡 검색의 초성 검색(`ChosungSearch`) 입력. {@code titleChosung}/{@code artistChosung} 파생
 * 컬럼은 본 유틸의 결과를 영속해 {@code titleChosung LIKE 'ㅂㄹㄷ%'} prefix 매칭을 index 로 태운다
 * (spec {@code song-search-and-filter.md} §5-1/§5-7).
 *
 * <p>파생 규칙 (Q6 결정 — 영문/숫자 원문 소문자 보존):
 * <ul>
 * <li>한글 음절(가–힣) → 초성 자모 1글자.</li>
 * <li>이미 자모 consonant(ㄱ–ㅎ) → 그대로.</li>
 * <li>그 외(영문/숫자/공백/기호) → {@code Character.toLowerCase} 로 보존 — 혼합 제목
 * ({@code "Day 6"} → {@code "day 6"}) 도 결정적으로 파생.</li>
 * </ul>
 */
public final class ChosungDeriver {

    private static final char[] CHOSUNG = {
            'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ',
            'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ',
    };

    private static final char HANGUL_BASE = 0xAC00;
    private static final char HANGUL_LAST = 0xD7A3;
    private static final int CHOSUNG_BLOCK = 588;
    private static final char JAMO_FIRST = 0x3131;
    private static final char JAMO_LAST = 0x314E;

    private ChosungDeriver() {
        // utility class
    }

    /**
     * 입력 문자열의 초성열을 반환한다. null 입력은 null 을 반환한다 (nullable 컬럼 파생).
     */
    public static String of(final String text) {
        if (text == null) {
            return null;
        }
        final StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            final char character = text.charAt(i);
            if (character >= HANGUL_BASE && character <= HANGUL_LAST) {
                builder.append(CHOSUNG[(character - HANGUL_BASE) / CHOSUNG_BLOCK]);
            } else if (character >= JAMO_FIRST && character <= JAMO_LAST) {
                builder.append(character);
            } else {
                builder.append(Character.toLowerCase(character));
            }
        }
        return builder.toString();
    }

    /**
     * 검색어가 초성 검색열인지 판별한다 — 공백 제거 후 모두 자모 consonant(ㄱ–ㅎ)면 true.
     * 완성형 한글/영문이 섞이면 false (완성형 경로 우선, spec §3).
     */
    public static boolean isChosungQuery(final String keyword) {
        if (keyword == null) {
            return false;
        }
        final String compact = keyword.replaceAll("\\s", "");
        if (compact.isEmpty()) {
            return false;
        }
        for (int i = 0; i < compact.length(); i++) {
            final char character = compact.charAt(i);
            if (character < JAMO_FIRST || character > JAMO_LAST) {
                return false;
            }
        }
        return true;
    }
}
