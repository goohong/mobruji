package com.mobruji.song.application.musicbrainz;

/**
 * MusicBrainz 503(rate limit) 응답이 backoff 재시도를 모두 소진하고도 지속될 때 던진다.
 *
 * <p>spec {@code musicbrainz-integration.md} §5-4 — 503 이 backoff 3회 모두 실패하면 rate limit 침해 위험이
 * 크므로 곡 단위 skip 이 아니라 **batch 전체 중단** 신호로 쓴다. {@link MusicBrainzBackfillCommand} 가 본 예외를
 * 잡아 loop 를 중단한다 (다른 5xx/timeout 은 곡 단위 graceful skip).
 */
public class MusicBrainzRateLimitException extends RuntimeException {

    public MusicBrainzRateLimitException(final String message) {
        super(message);
    }
}
