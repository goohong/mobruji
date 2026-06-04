package com.mobruji.song.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;

/**
 * {@link SongRepository#findMissingMbId()} / {@link SongRepository#findByMbId(String)} 회귀 가드 — #267/#268.
 *
 * <p>회귀 시나리오:
 * <ul>
 * <li>selective WHERE 가 빠지면 → 이미 mbId 채운 곡까지 MusicBrainz 재호출 (rate limit 침해).</li>
 * <li>저신뢰도 우선 정렬이 빠지면 → 재검증 가치 높은 곡 우선순위 손실.</li>
 * <li>findByMbId 가 깨지면 → UNIQUE(mb_id) 충돌 사전 회피 불가.</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class SongRepositoryMbIdQueryTest {

    @Autowired
    private SongRepository songRepository;

    private static Song song(final String title, final double confidence) {
        return Song.builder()
                .title(title).artist("artist-" + title)
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .metadataConfidence(confidence)
                .build();
    }

    private static Song songWithMbId(final String title, final String mbId) {
        return Song.builder()
                .title(title).artist("artist-" + title)
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .mbId(mbId)
                .build();
    }

    @Test
    @DisplayName("findMissingMbId: mbId IS NULL 곡만 반환 (selective WHERE 누락 회귀 가드)")
    void findMissingMbId_returnsOnlyNullMbId() {
        final Song missing = songRepository.save(song("mb-missing", 1.0));
        final Song present = songRepository.save(songWithMbId("mb-present", "mbid-present-1"));

        final List<Song> result = songRepository.findMissingMbId();

        assertThat(result).extracting(Song::getId).contains(missing.getId()).doesNotContain(present.getId());
    }

    @Test
    @DisplayName("findMissingMbId: metadataConfidence 오름차순 정렬 (저신뢰도 우선 회귀 가드)")
    void findMissingMbId_orderedByConfidenceAsc() {
        final Song high = songRepository.save(song("mb-conf-high-" + System.nanoTime(), 0.99));
        final Song low = songRepository.save(song("mb-conf-low-" + System.nanoTime(), 0.10));

        final List<Song> result = songRepository.findMissingMbId();

        final List<Long> ordered = result.stream()
                .map(Song::getId)
                .filter(id -> id.equals(high.getId()) || id.equals(low.getId()))
                .toList();
        assertThat(ordered).containsExactly(low.getId(), high.getId());
    }

    @Test
    @DisplayName("findByMbId: 일치하는 mbId 곡 반환, 없으면 empty")
    void findByMbId_lookup() {
        final Song saved = songRepository.save(songWithMbId("mb-lookup", "mbid-lookup-1"));

        assertThat(songRepository.findByMbId("mbid-lookup-1")).get()
                .extracting(Song::getId).isEqualTo(saved.getId());
        assertThat(songRepository.findByMbId("mbid-no-such")).isEmpty();
    }
}
