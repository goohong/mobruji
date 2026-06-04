package com.mobruji.song.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

/**
 * {@link AudioAnalysisScheduledBackfill} 단위 테스트. 정기 batch 가 metadataSource 기반으로 selection 을
 * 수행하고 {@link SongAudioBackfillCommand} 에 위임하는지 검증한다.
 */
@SuppressWarnings("unchecked")
class AudioAnalysisScheduledBackfillTest {

    private static Song song(final String title, final MetadataSource source) {
        return Song.builder()
                .title(title)
                .artist("artist-" + title)
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(source)
                .lowMidi(60)
                .highMidi(70)
                .build();
    }

    @Test
    @DisplayName("runScheduledBackfill: repository selective query 결과를 그대로 backfillCommand 에 위임한다")
    void runScheduledBackfill_delegatesCandidatesFromQuery() {
        // given: repository selective query 가 2곡 후보를 돌려준다고 가정
        final Song s1 = song("seed", MetadataSource.MANUAL_SEED);
        final Song s3 = song("new", MetadataSource.MANUAL_SEED);

        final SongRepository repo = mock(SongRepository.class);
        when(repo.findCandidatesForBackfill(0.6)).thenReturn(List.of(s1, s3));

        final SongAudioBackfillCommand backfillCommand = mock(SongAudioBackfillCommand.class);
        when(backfillCommand.runBackfill(any(List.class), eq(0.6)))
                .thenReturn(new SongAudioBackfillCommand.BackfillSummary(2, 2, 2, 0, 0, 0));

        final AudioAnalysisScheduledBackfill scheduler = new AudioAnalysisScheduledBackfill(repo, backfillCommand);

        // when
        scheduler.runScheduledBackfill();

        // then: query 결과를 그대로 위임 (in-memory 재필터링 없음)
        verify(backfillCommand).runBackfill(List.of(s1, s3), 0.6);
    }

    @Test
    @DisplayName("runScheduledBackfill: query 결과가 비면 backfillCommand 호출 없이 종료")
    void runScheduledBackfill_noTargets_skips() {
        final SongRepository repo = mock(SongRepository.class);
        when(repo.findCandidatesForBackfill(0.6)).thenReturn(List.of());

        final SongAudioBackfillCommand backfillCommand = mock(SongAudioBackfillCommand.class);

        final AudioAnalysisScheduledBackfill scheduler = new AudioAnalysisScheduledBackfill(repo, backfillCommand);

        scheduler.runScheduledBackfill();

        verify(backfillCommand, never()).runBackfill(any(List.class), anyDouble());
    }

    @Test
    @DisplayName("selectTargets: SongRepository#findCandidatesForBackfill 를 threshold 0.6 으로 위임 호출한다")
    void selectTargets_delegatesToRepositorySelectiveQuery() {
        final Song s1 = song("seed", MetadataSource.MANUAL_SEED);

        final SongRepository repo = mock(SongRepository.class);
        when(repo.findCandidatesForBackfill(0.6)).thenReturn(List.of(s1));

        final AudioAnalysisScheduledBackfill scheduler = new AudioAnalysisScheduledBackfill(
                repo, mock(SongAudioBackfillCommand.class));

        final List<Song> targets = scheduler.selectTargets();

        assertThat(targets).containsExactly(s1);
        verify(repo).findCandidatesForBackfill(0.6);
    }
}
