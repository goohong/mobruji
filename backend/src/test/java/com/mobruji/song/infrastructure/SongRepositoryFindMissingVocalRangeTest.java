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
 * {@link SongRepository#findMissingVocalRange()} 의 selective query 회귀 가드 — 이슈 #1739.
 *
 * <p>음역대(lowMidi/highMidi)가 비어 추천 풀에서 빠진 임포트 곡만 정밀 타겟하는지 검증한다. 한쪽이라도 NULL 이면
 * 후보이며, 둘 다 채워진 곡은 제외된다. 회귀 시나리오:
 *
 * <ul>
 * <li>OR 가 AND 로 회귀하면 → low 만 있고 high 가 없는(또는 그 반대) 곡이 누락된다.</li>
 * <li>NULL 판정이 빠지면 → 이미 음역대 보유 곡까지 후보에 섞여 불필요 재분석.</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class SongRepositoryFindMissingVocalRangeTest {

    @Autowired
    private SongRepository songRepository;

    private static Song song(final String title, final Integer lowMidi, final Integer highMidi) {
        return Song.builder()
                .title(title)
                .artist("artist-" + title)
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .lowMidi(lowMidi)
                .highMidi(highMidi)
                .build();
    }

    @Test
    @DisplayName("lowMidi/highMidi 둘 다 NULL 인 곡은 후보 (임포트 곡 — 추천 풀 밖)")
    void bothNull_isCandidate() {
        final Song missing = songRepository.save(song("missing-both", null, null));

        final List<Song> candidates = songRepository.findMissingVocalRange();

        assertThat(candidates).extracting(Song::getId).contains(missing.getId());
    }

    @Test
    @DisplayName("한쪽만 NULL 인 곡도 후보 (OR 회귀 가드)")
    void oneSideNull_isCandidate() {
        final Song lowOnly = songRepository.save(song("low-only", 50, null));
        final Song highOnly = songRepository.save(song("high-only", null, 70));

        final List<Song> candidates = songRepository.findMissingVocalRange();

        assertThat(candidates).extracting(Song::getId)
                .contains(lowOnly.getId(), highOnly.getId());
    }

    @Test
    @DisplayName("lowMidi/highMidi 둘 다 채워진 곡은 후보 제외")
    void bothPresent_isExcluded() {
        final Song complete = songRepository.save(song("complete", 50, 70));

        final List<Song> candidates = songRepository.findMissingVocalRange();

        assertThat(candidates).extracting(Song::getId).doesNotContain(complete.getId());
    }

    @Test
    @DisplayName("정렬: 내가 저장한 미보유 곡들의 상대 순서는 id 오름차순 (chunk 결정성 회귀 가드)")
    void candidates_areOrderedByIdAsc() {
        final Song first = songRepository.save(song("order-a", null, null));
        final Song second = songRepository.save(song("order-b", null, null));
        final Song third = songRepository.save(song("order-c", null, null));

        final List<Song> candidates = songRepository.findMissingVocalRange();

        final List<Long> myIds = candidates.stream()
                .map(Song::getId)
                .filter(id -> id.equals(first.getId())
                        || id.equals(second.getId())
                        || id.equals(third.getId()))
                .toList();
        assertThat(myIds).containsExactly(first.getId(), second.getId(), third.getId());
    }
}
