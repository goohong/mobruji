package com.mobruji.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

/**
 * {@link SongRepository#findCandidatesForBackfill(double)} 통합 — 정기 audio backfill 후보 selective query 검증
 * (rev 15 #226).
 *
 * <p>spec: {@code docs/features/audio-tooling-bootstrap.md} — confidence &lt; threshold 또는
 * AUDIO_ANALYSIS 가 아닌 source 의 곡만 후보로 잡아 불필요한 재분석을 막는다.
 */
@Transactional
@SpringBootTest
@ActiveProfiles("test")
class SongAudioBackfillCandidateQueryIntegrationTest {

    private static final double THRESHOLD = 0.6;

    @Autowired
    private SongRepository songRepository;

    @BeforeEach
    void setUp() {
        songRepository.deleteAll();
    }

    private static Song song(
            final String title,
            final MetadataSource source,
            final double confidence) {
        return Song.builder()
                .title(title)
                .artist("artist-" + title)
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(source)
                .metadataConfidence(confidence)
                .lowMidi(60)
                .highMidi(72)
                .build();
    }

    @Test
    @DisplayName("findCandidatesForBackfill: 임계 미만 confidence 또는 AUDIO_ANALYSIS 가 아닌 곡만 반환")
    void findCandidatesForBackfill_returnsLowConfidenceOrNonAudioAnalysisSongs() {
        // given:
        //  - lowConfidence: AUDIO_ANALYSIS 지만 confidence 가 임계 미만 → 후보
        //  - manualSeedHigh: 시드라 source != AUDIO_ANALYSIS → 후보
        //  - audioDoneHigh: AUDIO_ANALYSIS + confidence ≥ 임계 → 제외
        final Song lowConfidence = songRepository.save(song("low", MetadataSource.AUDIO_ANALYSIS, 0.4));
        final Song manualSeedHigh = songRepository.save(song("seed", MetadataSource.MANUAL_SEED, 1.0));
        songRepository.save(song("audio-done", MetadataSource.AUDIO_ANALYSIS, 0.9));

        // when
        final List<Song> candidates = songRepository.findCandidatesForBackfill(THRESHOLD);

        // then: 후보 2건만 — id ASC 정렬
        assertThat(candidates)
                .extracting(Song::getId)
                .containsExactly(lowConfidence.getId(), manualSeedHigh.getId());
    }

    @Test
    @DisplayName("findCandidatesForBackfill: 임계 이상 + AUDIO_ANALYSIS 곡만 있으면 빈 결과")
    void findCandidatesForBackfill_emptyWhenAllConverged() {
        songRepository.save(song("a", MetadataSource.AUDIO_ANALYSIS, 0.8));
        songRepository.save(song("b", MetadataSource.AUDIO_ANALYSIS, 0.95));

        final List<Song> candidates = songRepository.findCandidatesForBackfill(THRESHOLD);

        assertThat(candidates).isEmpty();
    }
}
