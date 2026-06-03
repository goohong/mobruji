package com.mobruji.song.application.catalogimport;

import java.util.Optional;

/**
 * (제목, 아티스트) 로 외부 CC0 출처에서 곡 메타데이터를 조회하는 추상 — spec {@code song-catalog-expansion.md}
 * §5-3. 1차 구현은 {@link MusicBrainzSongMetadataLookupClient}.
 *
 * <p>graceful 계약 — 매칭 실패/외부 오류 시 절대 예외를 던지지 않고 {@link Optional#empty()} 를 반환한다.
 * 임포트 배치가 곡 단위로 격리 진행되도록 보장 (album cover lookup 과 동일 정책).
 */
public interface SongMetadataLookupClient {

    Optional<ImportedSongMetadata> lookupMetadata(String title, String artist);
}
