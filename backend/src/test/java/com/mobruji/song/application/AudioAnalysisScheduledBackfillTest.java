package com.mobruji.song.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
    @DisplayName("runScheduledBackfill: AUDIO_ANALYSIS 아닌 곡만 골라 backfillCommand 에 위임한다")
    void runScheduledBackfill_filtersByMetadataSource() {
        // given: 3곡 — 2곡은 미분석(시드/외부), 1곡은 이미 audio 분석 완료
        final Song s1 = song("seed", MetadataSource.MANUAL_SEED);
        final Song s2 = song("audio-done", MetadataSource.AUDIO_ANALYSIS);
        final Song s3 = song("new", MetadataSource.MANUAL_SEED);

        final SongRepository repo = mock(SongRepository.class);
        when(repo.findAll()).thenReturn(List.of(s1, s2, s3));

        final SongAudioBackfillCommand backfillCommand = mock(SongAudioBackfillCommand.class);
        when(backfillCommand.runBackfill(any(List.class), eq(0.6)))
                .thenReturn(new SongAudioBackfillCommand.BackfillSummary(2, 2, 2, 0, 0));

        final AudioAnalysisScheduledBackfill scheduler = new AudioAnalysisScheduledBackfill(repo, backfillCommand);

        // when
        scheduler.runScheduledBackfill();

        // then: AUDIO_ANALYSIS 곡(s2)은 제외, 나머지 2곡만 위임
        verify(backfillCommand).runBackfill(List.of(s1, s3), 0.6);
    }

    @Test
    @DisplayName("runScheduledBackfill: 대상 곡이 없으면 backfillCommand 호출 없이 종료")
    void runScheduledBackfill_noTargets_skips() {
        final Song s1 = song("audio-done", MetadataSource.AUDIO_ANALYSIS);

        final SongRepository repo = mock(SongRepository.class);
        when(repo.findAll()).thenReturn(List.of(s1));

        final SongAudioBackfillCommand backfillCommand = mock(SongAudioBackfillCommand.class);

        final AudioAnalysisScheduledBackfill scheduler = new AudioAnalysisScheduledBackfill(repo, backfillCommand);

        scheduler.runScheduledBackfill();

        verify(backfillCommand, never()).runBackfill(any(List.class), any(Double.class));
    }

    @Test
    @DisplayName("selectTargets: metadataSource != AUDIO_ANALYSIS 인 곡만 반환")
    void selectTargets_returnsOnlyNonAudioAnalysis() {
        final Song s1 = song("seed", MetadataSource.MANUAL_SEED);
        final Song s2 = song("audio-done", MetadataSource.AUDIO_ANALYSIS);

        final SongRepository repo = mock(SongRepository.class);
        when(repo.findAll()).thenReturn(List.of(s1, s2));

        final AudioAnalysisScheduledBackfill scheduler = new AudioAnalysisScheduledBackfill(
                repo, mock(SongAudioBackfillCommand.class));

        final List<Song> targets = scheduler.selectTargets();

        assertThat(targets).containsExactly(s1);
    }
}
