package com.mobruji.song.application.musicbrainz;

import java.util.Objects;

/**
 * MusicBrainz recording 검색 top-hit 매칭 결과 — spec {@code musicbrainz-integration.md} §5-7.
 *
 * @param mbId  Recording UUID (필수)
 * @param isrc  검색 응답에 포함된 첫 ISRC (없으면 null — 상세 lookup 으로 보강 가능)
 * @param score MusicBrainz 반환 매칭 점수 (0~100, 내림차순 정렬된 top-hit). {@code score / 100.0} 이 신뢰도.
 */
public record MusicBrainzMatch(
        String mbId,
        String isrc,
        int score
) {

    public MusicBrainzMatch {
        Objects.requireNonNull(mbId, "mbId must not be null");
    }

    /** 매칭 신뢰도 (0.0~1.0) — MusicBrainz score 를 100 으로 정규화. */
    public double confidence() {
        return score / 100.0;
    }
}
