package com.mobruji.song.application.catalogimport;

/**
 * MusicBrainz recording browse 가 한국 대중가요 후보로 가져오는 단일 recording 메타 — spec
 * {@code song-catalog-expansion.md} §5-3 (#1705). {@link ImportedSongMetadata} 와 달리 {@code title}/{@code artist}
 * 까지 외부에서 발견(discover)한다 — 수기 (title, artist) 후보 없이 아티스트 단위 browse 로 대량 수집하기 위함.
 *
 * <p>CC0 메타데이터만 담는다 (제목/아티스트/MBID/ISRC/발매연도/장르 태그). 음역대/key/tempo 는 포함하지 않는다 —
 * 외부 출처가 제공하지 않으며 자체 분석이 권위 (spec §5-0). 이미지·가사·원본 음원도 가져오지 않는다 (CLAUDE.md §4 보안).
 *
 * @param mbId        MusicBrainz recording MBID (없으면 null — 멱등/영속 키)
 * @param title       곡 제목 (recording {@code title})
 * @param artist      대표 아티스트 표기 (recording {@code artist-credit[0]})
 * @param isrc        International Standard Recording Code (있으면 멱등 키로도 사용)
 * @param releaseYear 최초 발매 연도
 * @param genre       대표 장르 태그 (외부 태그 원문 — 미보유 시 null)
 */
public record BrowsedRecording(
        String mbId,
        String title,
        String artist,
        String isrc,
        Integer releaseYear,
        String genre
) {
}
