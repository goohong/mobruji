package com.mobruji.song.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository.MetadataSourceCount;

/**
 * {@link SongRepository} 잔여 query method 회귀 가드 — closes #466.
 *
 * <p>대상 3건:
 * <ul>
 * <li>{@link SongRepository#countByMetadataSource()} — admin 통계 API group-by 결과 결정성</li>
 * <li>{@link SongRepository#searchByKeyword(String)} — LIKE 패턴 (대소문자/부분일치) + 정렬</li>
 * <li>{@link SongRepository#findMissingAlbumCover()} — selective {@code albumCoverUrl IS NULL} + id ASC 정렬</li>
 * </ul>
 *
 * <p>회귀 시나리오:
 * <ul>
 * <li>group-by 가 빠지면 → 통계 응답이 row 단위 중복.</li>
 * <li>LIKE 패턴이 case-sensitive 로 회귀하면 → 한/영 혼용 검색 누락.</li>
 * <li>selective WHERE 가 빠지면 → 이미 cover 채워진 곡까지 외부 API 재호출 (#322).</li>
 * <li>정렬이 빠지거나 DESC 로 회귀하면 → backfill 순서 비결정 / 로깅 불안정.</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class SongRepositoryQueryMethodsTest {

    @Autowired
    private SongRepository songRepository;

    private static Song song(
            final String title,
            final String artist,
            final MetadataSource source) {
        return Song.builder()
                .title(title)
                .artist(artist)
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(source)
                .metadataConfidence(1.0)
                .lowMidi(60)
                .highMidi(70)
                .build();
    }

    private static Song songWithCover(
            final String title,
            final String artist,
            final String albumCoverUrl) {
        return Song.builder()
                .title(title)
                .artist(artist)
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .metadataConfidence(1.0)
                .lowMidi(60)
                .highMidi(70)
                .albumCoverUrl(albumCoverUrl)
                .build();
    }

    // ───────────────────── countByMetadataSource ─────────────────────

    @Test
    @DisplayName("countByMetadataSource: source 별로 group 되어 row 가 합쳐진다 (group-by 누락 회귀 가드)")
    void countByMetadataSource_groupsBySource() {
        // given: 같은 source 3건, 다른 source 2건
        songRepository.save(song("count-a", "artist-a", MetadataSource.MANUAL_SEED));
        songRepository.save(song("count-b", "artist-b", MetadataSource.MANUAL_SEED));
        songRepository.save(song("count-c", "artist-c", MetadataSource.MANUAL_SEED));
        songRepository.save(song("count-d", "artist-d", MetadataSource.AUDIO_ANALYSIS));
        songRepository.save(song("count-e", "artist-e", MetadataSource.AUDIO_ANALYSIS));

        // when
        final List<MetadataSourceCount> rows = songRepository.countByMetadataSource();

        // then: source 별로 row 1개씩만 존재 (group-by 누락 시 5건이 그대로 반환)
        final Map<MetadataSource, Long> bySource = rows.stream()
                .collect(Collectors.toMap(MetadataSourceCount::getMetadataSource, MetadataSourceCount::getCount));
        assertThat(bySource.get(MetadataSource.MANUAL_SEED)).isGreaterThanOrEqualTo(3L);
        assertThat(bySource.get(MetadataSource.AUDIO_ANALYSIS)).isGreaterThanOrEqualTo(2L);
    }

    @Test
    @DisplayName("countByMetadataSource: 본 테스트에서 추가한 source 별 정확한 증분만 검증 (다른 테스트 row 영향 차단)")
    void countByMetadataSource_returnsDistinctSourceKeys() {
        // given: 기존 row 영향을 차단하기 위해 사전 카운트 차감 방식으로 검증
        final Map<MetadataSource, Long> before = songRepository.countByMetadataSource().stream()
                .collect(Collectors.toMap(MetadataSourceCount::getMetadataSource, MetadataSourceCount::getCount));
        songRepository.save(song("delta-a", "artist-a", MetadataSource.EXTERNAL_API));
        songRepository.save(song("delta-b", "artist-b", MetadataSource.EXTERNAL_API));

        // when
        final Map<MetadataSource, Long> after = songRepository.countByMetadataSource().stream()
                .collect(Collectors.toMap(MetadataSourceCount::getMetadataSource, MetadataSourceCount::getCount));

        // then: source 키는 distinct 해야 하고 (group-by 결과), EXTERNAL_API 만 2 증가
        final long delta = after.getOrDefault(MetadataSource.EXTERNAL_API, 0L)
                - before.getOrDefault(MetadataSource.EXTERNAL_API, 0L);
        assertThat(delta).isEqualTo(2L);
    }

    // ───────────────────── searchByKeyword ─────────────────────

    @Test
    @DisplayName("searchByKeyword: title 부분일치 LIKE 대소문자 무시 (lower 회귀 가드)")
    void searchByKeyword_titleCaseInsensitivePartialMatch() {
        // given
        final Song saved = songRepository.save(song("AwesomeSong", "some-artist", MetadataSource.MANUAL_SEED));

        // when: 소문자 키워드로 대문자 title 검색
        final List<Song> hits = songRepository.searchByKeyword("awesome");

        // then: 본 케이스 포함. lower() 가 빠지면 H2 기본 대소문자 동작에 따라 누락된다.
        assertThat(hits).extracting(Song::getId).contains(saved.getId());
    }

    @Test
    @DisplayName("searchByKeyword: artist 부분일치도 LIKE OR 절로 매칭")
    void searchByKeyword_artistPartialMatch() {
        // given: title 은 키워드 미포함, artist 만 키워드 포함
        final Song saved = songRepository.save(song("title-x", "UniqueArtistZeta", MetadataSource.MANUAL_SEED));

        // when
        final List<Song> hits = songRepository.searchByKeyword("uniqueartistzeta");

        // then: artist OR 절로 매칭. OR 절이 빠지면 본 케이스 누락.
        assertThat(hits).extracting(Song::getId).contains(saved.getId());
    }

    @Test
    @DisplayName("searchByKeyword: 매칭 없으면 빈 리스트 (null 반환 회귀 가드)")
    void searchByKeyword_noMatch_returnsEmpty() {
        // given: 키워드 미포함 곡만 저장
        songRepository.save(song("alpha", "beta", MetadataSource.MANUAL_SEED));

        // when
        final List<Song> hits = songRepository.searchByKeyword("zzzz-no-match-zzzz-" + System.nanoTime());

        // then
        assertThat(hits).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("searchByKeyword: 결과는 title 오름차순 정렬 (ORDER BY 누락 회귀 가드)")
    void searchByKeyword_resultsOrderedByTitleAsc() {
        // given: 같은 키워드 매칭, title 알파벳 순서 역순 저장
        final String keyword = "kwsort" + System.nanoTime();
        songRepository.save(song(keyword + "-charlie", "x", MetadataSource.MANUAL_SEED));
        songRepository.save(song(keyword + "-alpha", "x", MetadataSource.MANUAL_SEED));
        songRepository.save(song(keyword + "-bravo", "x", MetadataSource.MANUAL_SEED));

        // when
        final List<Song> hits = songRepository.searchByKeyword(keyword);

        // then: title ASC 로 정렬. ORDER BY 가 빠지면 insertion order 로 회귀.
        assertThat(hits).extracting(Song::getTitle).containsExactly(
                keyword + "-alpha",
                keyword + "-bravo",
                keyword + "-charlie");
    }

    // ───────────────────── findMissingAlbumCover ─────────────────────

    @Test
    @DisplayName("findMissingAlbumCover: albumCoverUrl IS NULL 만 반환 (selective WHERE 누락 회귀 가드)")
    void findMissingAlbumCover_returnsOnlyNullCoverRows() {
        // given: cover 있는 곡 / 없는 곡 혼재
        final Song missing = songRepository.save(song("cover-missing", "a", MetadataSource.MANUAL_SEED));
        final Song present = songRepository.save(
                songWithCover("cover-present", "b", "https://example.com/c.jpg"));

        // when
        final List<Song> missingCover = songRepository.findMissingAlbumCover();

        // then: cover 없는 곡만 포함, cover 있는 곡은 제외. WHERE 가 빠지면 본 분리가 깨진다.
        final List<Long> ids = missingCover.stream().map(Song::getId).toList();
        assertThat(ids).contains(missing.getId()).doesNotContain(present.getId());
    }

    @Test
    @DisplayName("findMissingAlbumCover: id 오름차순 정렬 (ORDER BY 누락 / DESC 회귀 가드)")
    void findMissingAlbumCover_orderedByIdAsc() {
        // given: cover 없는 곡 3건 순차 저장
        final Song first = songRepository.save(song("missing-order-a", "a", MetadataSource.MANUAL_SEED));
        final Song second = songRepository.save(song("missing-order-b", "b", MetadataSource.MANUAL_SEED));
        final Song third = songRepository.save(song("missing-order-c", "c", MetadataSource.MANUAL_SEED));

        // when
        final List<Song> missingCover = songRepository.findMissingAlbumCover();

        // then: 본 테스트가 저장한 3건의 상대 순서만 검증 (절대 위치 검증 불가 — 다른 테스트 row 영향).
        // DESC 회귀 시 본 케이스가 깨진다.
        final List<Long> myIds = missingCover.stream()
                .map(Song::getId)
                .filter(id -> id.equals(first.getId())
                        || id.equals(second.getId())
                        || id.equals(third.getId()))
                .toList();
        assertThat(myIds).containsExactly(first.getId(), second.getId(), third.getId());
    }

    @Test
    @DisplayName("findMissingAlbumCover: 모든 곡 cover 채워졌으면 빈 리스트 (#322 selective 보장)")
    void findMissingAlbumCover_allCovered_excludedFromResult() {
        // given: cover 있는 곡만 저장
        final Song covered = songRepository.save(
                songWithCover("all-covered-" + System.nanoTime(), "a", "https://example.com/x.jpg"));

        // when
        final List<Song> missingCover = songRepository.findMissingAlbumCover();

        // then: 본 곡은 후보에서 제외 (다른 테스트가 만든 null cover row 는 영향 무관)
        assertThat(missingCover).extracting(Song::getId).doesNotContain(covered.getId());
    }

    // ───────────────────── findAllWithVocalRange ─────────────────────

    @Test
    @DisplayName("findAllWithVocalRange: lowMidi/highMidi 둘 다 보유한 곡만 반환 — 미보유 곡은 제외 (#1744 추천 오염 차단)")
    void findAllWithVocalRange_returnsOnlyRangedSongs() {
        // given: 음역대 보유 곡 / lowMidi 만 누락 / highMidi 만 누락 / 둘 다 누락
        final Song ranged = songRepository.save(songWithRange("ranged-both", "a", 60, 70));
        final Song onlyHigh = songRepository.save(songWithRange("only-high", "b", null, 70));
        final Song onlyLow = songRepository.save(songWithRange("only-low", "c", 60, null));
        final Song neither = songRepository.save(songWithRange("neither", "d", null, null));

        // when
        final List<Long> ids = songRepository.findAllWithVocalRange().stream().map(Song::getId).toList();

        // then: 둘 다 보유한 곡만 포함, 한쪽이라도 null 이면 제외 (voiceFit 실계산 가능 곡만)
        assertThat(ids)
                .contains(ranged.getId())
                .doesNotContain(onlyHigh.getId(), onlyLow.getId(), neither.getId());
    }

    @Test
    @DisplayName("findAllWithVocalRange: id 오름차순 정렬 (jitter 입력 결정성 가드)")
    void findAllWithVocalRange_orderedByIdAsc() {
        // given: 음역대 보유 곡 3건 순차 저장
        final Song first = songRepository.save(songWithRange("range-order-a", "a", 60, 70));
        final Song second = songRepository.save(songWithRange("range-order-b", "b", 61, 71));
        final Song third = songRepository.save(songWithRange("range-order-c", "c", 62, 72));

        // when
        final List<Long> myIds = songRepository.findAllWithVocalRange().stream()
                .map(Song::getId)
                .filter(id -> id.equals(first.getId())
                        || id.equals(second.getId())
                        || id.equals(third.getId()))
                .toList();

        // then: 저장 순서(id ASC) 그대로 — ORDER BY 누락/DESC 회귀 시 깨진다.
        assertThat(myIds).containsExactly(first.getId(), second.getId(), third.getId());
    }

    private static Song songWithRange(
            final String title,
            final String artist,
            final Integer lowMidi,
            final Integer highMidi) {
        return Song.builder()
                .title(title)
                .artist(artist)
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .metadataConfidence(1.0)
                .lowMidi(lowMidi)
                .highMidi(highMidi)
                .build();
    }
}
