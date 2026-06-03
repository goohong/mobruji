package com.mobruji.song.application.albumcover;

import java.util.regex.Pattern;

/**
 * 앨범 커버 외부 검색어 정규화 — ADR 0029 가 식별한 자유 텍스트 fuzzy 매칭 약점(괄호 주석 / {@code feat.} 표기 /
 * 대괄호 부가정보)을 외부 검색 전에 제거해 iTunes·MusicBrainz 매칭율을 높인다.
 *
 * <p>노래방/스트리밍 메타에 흔한 노이즈를 <b>보수적으로만</b> 제거한다 — 과도한 제거로 정상 매칭을 깨지 않도록,
 * 정규화 결과가 blank 가 되면 원문(trim)을 그대로 사용한다.
 *
 * <ul>
 * <li>괄호류 segment 제거: {@code (...)} {@code [...]} <code>{...}</code> {@code <...>} {@code 【...】}
 * — {@code (Inst.)} {@code (Live)} {@code [MV]} 같은 부가정보.</li>
 * <li>{@code feat.} / {@code ft.} / {@code featuring} 절 제거 — {@code A feat. B} 형태의 협연 표기.</li>
 * <li>연속 공백 1칸 축약 + trim.</li>
 * </ul>
 */
final class AlbumCoverSearchTerms {

    /** {@code (...)} {@code [...]} <code>{...}</code> {@code <...>} {@code 【...】} 한 쌍을 통째로 매칭. */
    private static final Pattern BRACKETED = Pattern.compile("[(\\[{<【][^)\\]}>】]*[)\\]}>】]");

    /** 공백으로 분리된 {@code feat. / ft. / featuring} 협연 절 — 키워드부터 문자열 끝까지 제거. */
    private static final Pattern FEAT_CLAUSE = Pattern.compile("(?i)\\s+(?:feat|ft|featuring)\\.?\\s.*$");

    private static final Pattern MULTI_SPACE = Pattern.compile("\\s{2,}");

    private AlbumCoverSearchTerms() {
    }

    /**
     * 검색어 1개를 정규화한다. {@code null} 은 {@code null} 그대로 반환한다(호출 측 blank 검사 보존).
     *
     * @return 노이즈 제거 후 trim 된 검색어. 제거 결과가 blank 면 원문 trim.
     */
    static String normalize(final String raw) {
        if (raw == null) {
            return null;
        }
        final String withoutBrackets = BRACKETED.matcher(raw).replaceAll(" ");
        final String withoutFeat = FEAT_CLAUSE.matcher(withoutBrackets).replaceAll(" ");
        final String collapsed = MULTI_SPACE.matcher(withoutFeat).replaceAll(" ").trim();
        return collapsed.isBlank() ? raw.trim() : collapsed;
    }
}
