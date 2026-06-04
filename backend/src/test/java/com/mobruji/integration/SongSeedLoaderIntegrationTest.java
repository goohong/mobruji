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
    @DisplayName("기존 row의 genre가 시드와 다르면 재분류로 갱신된다 (#1675)")
    void existingRowWithStaleGenre_isReclassified() throws Exception {
        // given: 시드 JSON에는 "발라드"인 "Beautiful"/Crush를 구 장르 "R&B"로 미리 저장 (#1672 이전 row 시뮬레이션)
        final Song legacy = songRepository.save(Song.builder()
                .title("Beautiful").artist("Crush")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .genre("R&B")
                .lowMidi(60).highMidi(74)
                .build());
        final Long legacyId = legacy.getId();
        assertThat(legacy.getGenre()).isEqualTo("R&B");

        // when: 시드 로더 실행
        loader.run(null);

        // then: 같은 row의 genre가 시드 값으로 재분류됨 (새 row 생성 X)
        final Song reloaded = songRepository.findById(legacyId).orElseThrow();
        assertThat(reloaded.getGenre()).isEqualTo("발라드");
    }

    @Test
    @DisplayName("기존 row의 genre가 시드와 같으면 갱신하지 않는다 (idempotent)")
    void existingRowWithSameGenre_isNotTouched() throws Exception {
        // given: 시드 1회 적재 후 같은 row의 updatedAt 기록
        loader.run(null);
        final Song afterSeed = songRepository.findByTitleAndArtist("Beautiful", "Crush").orElseThrow();
        final java.time.LocalDateTime firstUpdatedAt = afterSeed.getUpdatedAt();

        // when: 시드 재적재 (genre 동일)
        loader.run(null);

        // then: genre 동일 + updatedAt 갱신 없음 (불필요한 dirty write 회피)
        final Song reloaded = songRepository.findByTitleAndArtist("Beautiful", "Crush").orElseThrow();
        assertThat(reloaded.getGenre()).isEqualTo("발라드");
        assertThat(reloaded.getUpdatedAt()).isEqualTo(firstUpdatedAt);
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

    @Test
    @DisplayName("충돌 해소 (#276, spec §5-7): seed → audio-analysis 갱신 → seed 재적재 시 audio 값이 보존된다")
    void seedReload_afterAudioAnalysisBackfill_preservesAudioValues() throws Exception {
        // given: 시드 최초 적재 — "벚꽃 엔딩" 이 lowMidi=57, highMidi=76 (MANUAL_SEED) 으로 들어감
        loader.run(null);
        final Song afterSeed = songRepository.findByTitleAndArtist("벚꽃 엔딩", "버스커 버스커").orElseThrow();
        assertThat(afterSeed.getLowMidi()).isEqualTo(57);
        assertThat(afterSeed.getHighMidi()).isEqualTo(76);
        final Long sameRowId = afterSeed.getId();

        // when: audio-analysis backfill 시뮬레이션 — 임의 lowMidi/highMidi 로 갱신 시도
        // (spec §5-7 priority 3: AUDIO_ANALYSIS 가 MANUAL_SEED 의 null 자리만 채워야 하지만, 본 회귀는
        //  "seed 재적재 시점에서 기존 row 값이 보존되는가" 한 측면만 잠근다 — backfillMissingFields 의 의미)
        final Song audioUpdated = songRepository.findById(sameRowId).orElseThrow();
        audioUpdated.backfillFromAudioAnalysis(
                new com.mobruji.song.domain.AudioAnalysisResult(
                        60, 73, "A", 132.0, 200.0, 0.8, "analyze-py-test"),
                0.6);
        songRepository.save(audioUpdated);
        assertThat(songRepository.findById(sameRowId).orElseThrow().getLowMidi()).isEqualTo(60);

        // and: 시드를 한 번 더 적재 (배포 재기동 시나리오)
        loader.run(null);

        // then: 같은 row 가 살아 있고, audio-analysis 가 쓴 값이 seed 재적재로 덮이지 않음
        //       (spec §5-7 의 핵심 의미: seed 재적재는 null-only backfill, 기존 값 보존)
        final Song reloaded = songRepository.findById(sameRowId).orElseThrow();
        assertThat(reloaded.getLowMidi())
                .as("seed 재적재가 audio-analysis 갱신값을 덮어쓰면 안 됨 (spec §5-7)")
                .isEqualTo(60);
        assertThat(reloaded.getHighMidi())
                .as("seed 재적재가 audio-analysis 갱신값을 덮어쓰면 안 됨 (spec §5-7)")
                .isEqualTo(73);
        // 새 row 생성 없이 같은 id 가 유지돼야 함
        assertThat(songRepository.findByTitleAndArtist("벚꽃 엔딩", "버스커 버스커").orElseThrow().getId())
                .isEqualTo(sameRowId);
    }

    @Test
    @DisplayName("충돌 해소 (#276, spec §5-7): lowMidi/highMidi 가 비대칭으로 채워진 row 도 seed 재적재로 null 자리만 backfill")
    void seedReload_partiallyFilledRow_backfillsOnlyNullSlots() throws Exception {
        // given: 운영자가 highMidi 만 수동 입력해둔 "벚꽃 엔딩" 시뮬레이션 (lowMidi=null)
        final Song partial = songRepository.save(Song.builder()
                .title("벚꽃 엔딩").artist("버스커 버스커")
                .keyOriginal(MusicalKey.A_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .highMidi(80) // 운영자가 임의로 설정한 값, seed 값(76)과 다름
                .build());
        final Long partialId = partial.getId();

        // when
        loader.run(null);

        // then: lowMidi 는 seed 의 57 로 backfill, highMidi 는 운영자 값 80 그대로 보존
        final Song reloaded = songRepository.findById(partialId).orElseThrow();
        assertThat(reloaded.getLowMidi()).as("null 이던 lowMidi 는 seed 값으로 backfill")
                .isEqualTo(57);
        assertThat(reloaded.getHighMidi()).as("이미 값이 있던 highMidi 는 보존 (spec §5-7)")
                .isEqualTo(80);
    }
}
