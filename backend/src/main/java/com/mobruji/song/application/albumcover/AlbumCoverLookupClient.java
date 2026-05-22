package com.mobruji.song.application.albumcover;

import java.util.Optional;

/**
 * 외부 출처에서 곡의 앨범 커버 URL 을 조회하는 추상 — 이슈 #322.
 *
 * <p>구현체:
 * <ul>
 * <li>{@link ItunesAlbumCoverClient} — 1차 출처 (key 불필요, 즉시 활성).</li>
 * <li>(후속) MusicBrainz Cover Art Archive — 정확도 ↑, ISRC/mbid 필요.</li>
 * <li>(후속) Spotify — 커버리지 보완.</li>
 * </ul>
 *
 * <p>호출 의미:
 * <ul>
 * <li>매칭 성공 → {@code Optional.of(absoluteHttpsUrl)}. URL 은 backfill 후 그대로 영속화된다.</li>
 * <li>매칭 실패 / 외부 호출 실패 / 응답 parse 실패 → {@link Optional#empty()}. 호출 측은 다음 곡으로 진행.</li>
 * </ul>
 *
 * <p>구현체는 외부 의존(예외/timeout)을 자체적으로 격리하고 절대 예외를 전파하지 않는다 (graceful degradation).
 */
public interface AlbumCoverLookupClient {

    Optional<String> lookupAlbumCoverUrl(String title, String artist);
}
