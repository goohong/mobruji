package com.mobruji.song.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.OptionalInt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.mobruji.song.application.AudioBackfillOnDemandService.TriggerResult;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

/**
 * {@link AudioBackfillOnDemandService} 단위 테스트 — 후보 selection / limit / dryRun 분기 / 비동기 위임을
 * {@link SongRepository} 와 {@link AudioBackfillExecutor} mock 으로 검증한다.
 */
class AudioBackfillOnDemandServiceTest {

    private static Song missingRangeSong(final String title) {
        return Song.builder()
                .title(title)
                .artist("artist-" + title)
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
    }

    @Test
    @DisplayName("dryRun=true: 후보 집계만 반환하고 비동기 실행은 트리거하지 않는다")
    void dryRun_countsOnly_doesNotRun() {
        // given
        final SongRepository repo = mock(SongRepository.class);
        final AudioBackfillExecutor executor = mock(AudioBackfillExecutor.class);
        when(repo.findMissingVocalRange())
                .thenReturn(List.of(missingRangeSong("a"), missingRangeSong("b")));
        final AudioBackfillOnDemandService service = new AudioBackfillOnDemandService(repo, executor);

        // when
        final TriggerResult result = service.trigger(
                AudioBackfillOnDemandService.TARGET_MISSING_RANGE, OptionalInt.empty(), true);

        // then
        assertThat(result.candidates()).isEqualTo(2);
        assertThat(result.selected()).isEqualTo(2);
        assertThat(result.dryRun()).isTrue();
        assertThat(result.started()).isFalse();
        verify(executor, never()).runAsync(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    @DisplayName("dryRun=false + limit: 상한만큼 잘라 비동기 backfill 을 시작한다")
    void run_withLimit_startsAsyncOnSelected() {
        // given
        final SongRepository repo = mock(SongRepository.class);
        final AudioBackfillExecutor executor = mock(AudioBackfillExecutor.class);
        when(repo.findMissingVocalRange()).thenReturn(List.of(
                missingRangeSong("a"), missingRangeSong("b"), missingRangeSong("c")));
        final AudioBackfillOnDemandService service = new AudioBackfillOnDemandService(repo, executor);

        // when
        final TriggerResult result = service.trigger(
                AudioBackfillOnDemandService.TARGET_MISSING_RANGE, OptionalInt.of(2), false);

        // then
        assertThat(result.candidates()).isEqualTo(3);
        assertThat(result.selected()).isEqualTo(2);
        assertThat(result.dryRun()).isFalse();
        assertThat(result.started()).isTrue();

        @SuppressWarnings("unchecked") final ArgumentCaptor<List<Song>> captor = ArgumentCaptor.forClass(List.class);
        verify(executor).runAsync(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
    }

    @Test
    @DisplayName("dryRun=false + 후보 0건: 비동기 실행 없이 started=false")
    void run_noCandidates_doesNotStart() {
        // given
        final SongRepository repo = mock(SongRepository.class);
        final AudioBackfillExecutor executor = mock(AudioBackfillExecutor.class);
        when(repo.findMissingVocalRange()).thenReturn(List.of());
        final AudioBackfillOnDemandService service = new AudioBackfillOnDemandService(repo, executor);

        // when
        final TriggerResult result = service.trigger(
                AudioBackfillOnDemandService.TARGET_MISSING_RANGE, OptionalInt.empty(), false);

        // then
        assertThat(result.candidates()).isZero();
        assertThat(result.selected()).isZero();
        assertThat(result.started()).isFalse();
        verify(executor, never()).runAsync(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    @DisplayName("target=candidates: 신뢰도/출처 기반 후보 쿼리를 사용한다")
    void target_candidates_usesConfidenceQuery() {
        // given
        final SongRepository repo = mock(SongRepository.class);
        final AudioBackfillExecutor executor = mock(AudioBackfillExecutor.class);
        when(repo.findCandidatesForBackfill(anyDouble())).thenReturn(List.of(missingRangeSong("a")));
        final AudioBackfillOnDemandService service = new AudioBackfillOnDemandService(repo, executor);

        // when
        final TriggerResult result = service.trigger(
                AudioBackfillOnDemandService.TARGET_CANDIDATES, OptionalInt.empty(), true);

        // then
        assertThat(result.candidates()).isEqualTo(1);
        verify(repo).findCandidatesForBackfill(SongAudioBackfillCommand.DEFAULT_CONFIDENCE_THRESHOLD);
        verify(repo, never()).findMissingVocalRange();
    }
}
