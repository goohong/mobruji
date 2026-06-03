package com.mobruji.song.application.albumcover;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ChainedAlbumCoverLookupClient} 단위 테스트 — iTunes(1차) → CAA(폴백) 우선순위 검증.
 */
class ChainedAlbumCoverLookupClientTest {

    private final ItunesAlbumCoverClient itunes = mock(ItunesAlbumCoverClient.class);
    private final CoverArtArchiveAlbumCoverClient coverArtArchive = mock(CoverArtArchiveAlbumCoverClient.class);
    private final ChainedAlbumCoverLookupClient client = new ChainedAlbumCoverLookupClient(itunes, coverArtArchive);

    @Test
    @DisplayName("iTunes hit 이면 iTunes URL 반환 + CAA 미호출")
    void itunesHit_returnsItunesWithoutFallback() {
        when(itunes.lookupAlbumCoverUrl("t", "a"))
                .thenReturn(Optional.of("https://cdn.apple/600x600.jpg"));

        final Optional<String> result = client.lookupAlbumCoverUrl("t", "a");

        assertThat(result).contains("https://cdn.apple/600x600.jpg");
        verify(coverArtArchive, never()).lookupAlbumCoverUrl("t", "a");
    }

    @Test
    @DisplayName("iTunes miss 면 CAA 폴백 결과 반환")
    void itunesMiss_fallsBackToCoverArtArchive() {
        when(itunes.lookupAlbumCoverUrl("t", "a")).thenReturn(Optional.empty());
        when(coverArtArchive.lookupAlbumCoverUrl("t", "a"))
                .thenReturn(Optional.of("https://caa/500.jpg"));

        final Optional<String> result = client.lookupAlbumCoverUrl("t", "a");

        assertThat(result).contains("https://caa/500.jpg");
        verify(itunes).lookupAlbumCoverUrl("t", "a");
        verify(coverArtArchive).lookupAlbumCoverUrl("t", "a");
    }

    @Test
    @DisplayName("둘 다 miss 면 empty")
    void bothMiss_returnsEmpty() {
        when(itunes.lookupAlbumCoverUrl("t", "a")).thenReturn(Optional.empty());
        when(coverArtArchive.lookupAlbumCoverUrl("t", "a")).thenReturn(Optional.empty());

        final Optional<String> result = client.lookupAlbumCoverUrl("t", "a");

        assertThat(result).isEmpty();
    }
}
