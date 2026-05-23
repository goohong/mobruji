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
 * {@link SongRepository#findCandidatesForBackfill(double)} 의 selective query 회귀 가드 — closes #226.
 *
 * <p>PR #198 → #204 사이클에서 in-memory 필터({@code metadataSource != AUDIO_ANALYSIS}) 가
 * DB 측 selective query ({@code metadataConfidence < threshold OR metadataSource <> AUDIO_ANALYSIS}) 로
 * 갱신됐다. 본 테스트는 OR 조건 / threshold 경계 / 정렬을 회귀 가드한다.
 *
 * <p>회귀 시나리오 가시화:
 *
 * <ul>
 * <li>OR 가 AND 로 잘못 회귀하면 → high-confidence non-audio 곡이 누락된다.</li>
 * <li>threshold 비교가 {@code <=} 로 회귀하면 → 정확히 임계 곡이 의도와 다르게 후보 포함.</li>
 * <li>{@code AUDIO_ANALYSIS} enum 표기가 바뀌면 → 모든 audio 분석 곡이 후보 포함 (낭비).</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class SongRepositoryFindCandidatesForBackfillTest {

    private static final double THRESHOLD = 0.6;

    @Autowired
    private SongRepository songRepository;

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
                .highMidi(70)
                .build();
    }

    @Test
    @DisplayName("low-confidence audio 곡: confidence < threshold 면 source 가 AUDIO_ANALYSIS 여도 후보")
    void lowConfidenceAudioSong_isCandidate() {
        // given: audio 분석으로 채웠지만 confidence 낮은 곡
        final Song lowConfidenceAudio = songRepository.save(
                song("low-conf-audio", MetadataSource.AUDIO_ANALYSIS, 0.4));

        // when
        final List<Song> candidates = songRepository.findCandidatesForBackfill(THRESHOLD);

        // then: 임계 미만이라 재분석 후보
        assertThat(candidates).extracting(Song::getId).contains(lowConfidenceAudio.getId());
    }

    @Test
    @DisplayName("high-confidence non-audio 곡: source 가 AUDIO_ANALYSIS 가 아니면 confidence 1.0 이어도 후보 (OR 회귀 가드)")
    void highConfidenceNonAudioSong_isCandidate() {
        // given: 수기 시드(고신뢰지만 audio 분석 미수행)
        final Song manualHighConfidence = songRepository.save(
                song("manual-high", MetadataSource.MANUAL_SEED, 1.0));

        // when
        final List<Song> candidates = songRepository.findCandidatesForBackfill(THRESHOLD);

        // then: OR 조건의 후절(source != AUDIO_ANALYSIS) 로 후보. OR 가 AND 로 회귀하면 본 케이스가 빠진다.
        assertThat(candidates).extracting(Song::getId).contains(manualHighConfidence.getId());
    }

    @Test
    @DisplayName("high-confidence audio 곡: 두 조건 모두 거짓이라 후보 제외 (불필요 재분석 차단)")
    void highConfidenceAudioSong_isExcluded() {
        // given: audio 분석 + 고신뢰 → 재분석 가치 없음
        final Song highConfidenceAudio = songRepository.save(
                song("high-conf-audio", MetadataSource.AUDIO_ANALYSIS, 0.9));

        // when
        final List<Song> candidates = songRepository.findCandidatesForBackfill(THRESHOLD);

        // then: 후보 제외
        assertThat(candidates).extracting(Song::getId).doesNotContain(highConfidenceAudio.getId());
    }

    @Test
    @DisplayName("threshold 경계: confidence == threshold 인 audio 곡은 후보 제외 (strict-less 보장)")
    void exactlyAtThreshold_audioSong_isExcluded() {
        // given: 정확히 임계값
        final Song atThreshold = songRepository.save(
                song("at-threshold", MetadataSource.AUDIO_ANALYSIS, THRESHOLD));

        // when
        final List<Song> candidates = songRepository.findCandidatesForBackfill(THRESHOLD);

        // then: strict less (<) 라 임계 그 자체는 제외. <= 로 회귀하면 본 케이스가 잘못 포함된다.
        assertThat(candidates).extracting(Song::getId).doesNotContain(atThreshold.getId());
    }

    @Test
    @DisplayName("정렬: 결과 내 상대 순서는 id 오름차순 (selectTargets / 로깅 안정성 회귀 가드)")
    void candidates_areOrderedByIdAsc() {
        // given: 3건 후보 순차 저장. 동일 H2 인스턴스에 다른 테스트가 commit 한 row 가 있을 수 있어
        // "내가 저장한 3건의 상대 순서" 만 검증한다 (절대 위치는 검증하지 않음).
        final Song first = songRepository.save(song("ordering-a", MetadataSource.MANUAL_SEED, 1.0));
        final Song second = songRepository.save(song("ordering-b", MetadataSource.MANUAL_SEED, 1.0));
        final Song third = songRepository.save(song("ordering-c", MetadataSource.MANUAL_SEED, 1.0));

        // when
        final List<Song> candidates = songRepository.findCandidatesForBackfill(THRESHOLD);

        // then: 본 테스트가 저장한 3건이 id ASC 상대 순서를 유지해야 함.
        // DESC 로 회귀하거나 정렬이 빠지면 본 케이스가 깨진다.
        final List<Long> myIds = candidates.stream()
                .map(Song::getId)
                .filter(id -> id.equals(first.getId())
                        || id.equals(second.getId())
                        || id.equals(third.getId()))
                .toList();
        assertThat(myIds).containsExactly(first.getId(), second.getId(), third.getId());
    }
}
