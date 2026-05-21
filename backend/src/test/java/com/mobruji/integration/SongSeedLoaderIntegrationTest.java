package com.mobruji.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.mobruji.song.application.SongSeedLoader;
import com.mobruji.song.domain.Difficulty;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

/**
 * SongSeedLoader 라이프사이클 검증 — closes #122, #130.
 *
 * <p>SongSeedLoader는 `@Profile("!test")`라 test 컨텍스트에서 자동으로 빈 등록되지 않는다.
 * 본 테스트는 ObjectMapper/Repository를 주입받아 로더를 직접 인스턴스화하고 `run()`을 호출한다.
 *
 * <p>회귀 가드: 새 컬럼(lowMidi/highMidi/difficulty)이 null인 기존 row가 있을 때 backfill되어야 한다.
 * 이전 구현은 `count > 0` 이면 통째로 skip해서 #122의 운영 결함을 일으켰다.
 */
@SpringBootTest
@ActiveProfiles("test")
class SongSeedLoaderIntegrationTest {

    @Autowired
    private SongRepository songRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private SongSeedLoader loader;

    @BeforeEach
    void setUp() {
        songRepository.deleteAll();
        loader = new SongSeedLoader(songRepository, objectMapper);
    }

    @Test
    @DisplayName("최초 적재: 빈 테이블에 시드 entry 전체 insert")
    void firstLoad_insertsAllEntries() throws Exception {
        // given: 빈 테이블

        // when
        loader.run(null);

        // then: classpath:/songs-seed.json 전체 적재
        final List<Song> all = songRepository.findAll();
        assertThat(all).isNotEmpty();
        // 모든 시드 entry는 lowMidi/highMidi가 있으므로 difficulty가 채워져야 함
        assertThat(all).allSatisfy(song -> {
            assertThat(song.getLowMidi()).isNotNull();
            assertThat(song.getHighMidi()).isNotNull();
            assertThat(song.getDifficulty()).isNotNull();
        });
    }

    @Test
    @DisplayName("기존 row의 lowMidi/highMidi/difficulty가 null이면 backfill로 채워진다 (#122)")
    void existingRowWithNullMidi_isBackfilled() throws Exception {
        // given: 시드 JSON의 첫 곡 "벚꽃 엔딩"을 MIDI 없이 미리 저장 (구버전 row 시뮬레이션)
        final Song legacy = songRepository.save(Song.builder()
                .title("벚꽃 엔딩").artist("버스커 버스커")
                .keyOriginal(MusicalKey.A_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                // lowMidi/highMidi/difficulty 모두 null
                .build());
        assertThat(legacy.getLowMidi()).isNull();
        assertThat(legacy.getDifficulty()).isNull();
        final Long legacyId = legacy.getId();

        // when: 시드 로더 실행
        loader.run(null);

        // then: 같은 row가 backfill됨 (새 row 생성 X)
        final Song reloaded = songRepository.findById(legacyId).orElseThrow();
        assertThat(reloaded.getLowMidi()).isEqualTo(57);
        assertThat(reloaded.getHighMidi()).isEqualTo(76);
        assertThat(reloaded.getDifficulty()).isEqualTo(Difficulty.HARD);
    }

    @Test
    @DisplayName("기존 row에 이미 값이 있으면 보존 (운영 수정 안 덮음)")
    void existingRowWithValues_isPreserved() throws Exception {
        // given: 운영자가 수정한 값을 시뮬레이션 (시드 JSON과 다른 lowMidi)
        final Song customized = songRepository.save(Song.builder()
                .title("벚꽃 엔딩").artist("버스커 버스커")
                .keyOriginal(MusicalKey.A_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .lowMidi(60).highMidi(72) // 시드는 57/76인데 운영 수정값 가정
                .build());
        final Long id = customized.getId();
        final Difficulty originalDifficulty = customized.getDifficulty();

        // when
        loader.run(null);

        // then: 운영 값 그대로
        final Song reloaded = songRepository.findById(id).orElseThrow();
        assertThat(reloaded.getLowMidi()).isEqualTo(60);
        assertThat(reloaded.getHighMidi()).isEqualTo(72);
        assertThat(reloaded.getDifficulty()).isEqualTo(originalDifficulty);
    }

    @Test
    @DisplayName("2번 연속 실행해도 row 수가 안 늘어남 (idempotent)")
    void runTwice_isIdempotent() throws Exception {
        // when
        loader.run(null);
        final long afterFirst = songRepository.count();
        loader.run(null);
        final long afterSecond = songRepository.count();

        // then
        assertThat(afterFirst).isEqualTo(afterSecond);
        assertThat(afterFirst).isGreaterThan(0);
    }
}
