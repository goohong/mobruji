package com.mobruji.song.application.catalogimport;

/**
 * 메타-only 임포트가 외부 CC0 출처에서 가져오는 곡 메타데이터 묶음 — spec {@code song-catalog-expansion.md}
 * §5-1. 모든 필드 nullable (외부 응답에 따라 일부 누락 가능).
 *
 * <p>음역대/key/tempo 는 포함하지 않는다 — 외부 출처가 제공하지 않으며, 자체 분석이 권위 (spec §5-0).
 * {@code mbId} 는 현재 {@code Song} 영속 컬럼이 없어 로깅/멱등 진단 용도로만 보유한다 (영속 X).
 *
 * @param mbId        MusicBrainz recording MBID (영속하지 않음 — 로깅/추적용)
 * @param isrc        International Standard Recording Code (있으면 멱등 키로도 사용)
 * @param releaseYear 최초 발매 연도
 * @param genre       대표 장르 태그 (외부 태그 원문 — 미보유 시 null)
 */
public record ImportedSongMetadata(
        String mbId,
        String isrc,
        Integer releaseYear,
        String genre
) {
}
