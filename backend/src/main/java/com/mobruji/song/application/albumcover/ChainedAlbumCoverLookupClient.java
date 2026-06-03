package com.mobruji.song.application.albumcover;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 앨범 커버 출처 우선순위 체인 — ADR 0029. iTunes Search (1차) → MusicBrainz Cover Art Archive (폴백)
 * 순으로 시도해 첫 매칭 URL 을 반환한다.
 *
 * <p>{@link AlbumCoverBackfillCommand} 는 단일 {@link AlbumCoverLookupClient} 를 주입받으므로, 본 체인을
 * {@link Primary} 로 표시해 backfill 진입점이 우선순위 합성을 자동으로 사용하게 한다. 개별 출처
 * ({@link ItunesAlbumCoverClient} / {@link CoverArtArchiveAlbumCoverClient}) 는 비-primary Bean 으로 남아
 * 본 체인이 명시적으로 위임한다.
 *
 * <p>iTunes 1차 근거 — 무인증·즉시 매칭·한국 가요 커버리지 우위 (ADR 0029 Decision). CAA 폴백 근거 —
 * iTunes 미수록 곡 보완 + CC0 라이선스. 두 출처 모두 graceful (예외 미전파) 이라 체인은 단순 순차 위임으로
 * 충분하다.
 */
@Component
@Primary
public class ChainedAlbumCoverLookupClient implements AlbumCoverLookupClient {

    private static final Logger LOG = LoggerFactory.getLogger(ChainedAlbumCoverLookupClient.class);

    private final ItunesAlbumCoverClient itunesClient;
    private final CoverArtArchiveAlbumCoverClient coverArtArchiveClient;

    public ChainedAlbumCoverLookupClient(
            final ItunesAlbumCoverClient itunesClient,
            final CoverArtArchiveAlbumCoverClient coverArtArchiveClient) {
        this.itunesClient = itunesClient;
        this.coverArtArchiveClient = coverArtArchiveClient;
    }

    @Override
    public Optional<String> lookupAlbumCoverUrl(final String title, final String artist) {
        final Optional<String> primary = itunesClient.lookupAlbumCoverUrl(title, artist);
        if (primary.isPresent()) {
            return primary;
        }
        final Optional<String> fallback = coverArtArchiveClient.lookupAlbumCoverUrl(title, artist);
        if (fallback.isPresent()) {
            LOG.info("album cover resolved via cover-art-archive fallback");
        }
        return fallback;
    }
}
