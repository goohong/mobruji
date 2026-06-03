package com.mobruji.song.application.musicbrainz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * {@link MusicBrainzBackfillCommand} 단위 테스트 — selective query / 멱등 / 부분 실패 격리 / rate limit 중단 /
 * dryRun / UNIQUE 충돌 회피를 검증한다. 외부 호출은 {@link MusicBrainzClient} mock 으로 대체.
 */
class MusicBrainzBackfillCommandTest {

    private static MusicBrainzProperties props() {
        return new MusicBrainzProperties(
                "https://musicbrainz.org/ws/2",
                "mobruji-backend/0.1 (+test)",
                Duration.ofSeconds(5),
                Duration.ZERO,
                3,
                Duration.ofSeconds(1),
                90,
                5,
                new MusicBrainzProperties.Backfill(30, false));
    }

    private static Song seed(final String title) {
        return Song.builder()
                .title(title).artist("artist-" + title)
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
    }

    private static MusicBrainzBackfillCommand command(
            final SongRepository repository, final MusicBrainzClient client) {
        return new MusicBrainzBackfillCommand(repository, client, props(), new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("runBackfill: score≥임계 매칭은 적용+save, 무매칭/저score 는 통계만")
    void runBackfill_mixedOutcomes_countsCorrectly() {
        final Song matched = seed("matched");
        final Song lowScore = seed("low");
        final Song notFound = seed("none");
        final SongRepository repository = mock(SongRepository.class);
        when(repository.findMissingMbId()).thenReturn(List.of(matched, lowScore, notFound));
        when(repository.findByMbId(anyString())).thenReturn(Optional.empty());
        when(repository.findByIsrc(anyString())).thenReturn(Optional.empty());
        final MusicBrainzClient client = mock(MusicBrainzClient.class);
        when(client.searchTopRecording("matched", "artist-matched"))
                .thenReturn(Optional.of(new MusicBrainzMatch("mbid-ok", "KRA1", 95)));
        when(client.searchTopRecording("low", "artist-low"))
                .thenReturn(Optional.of(new MusicBrainzMatch("mbid-low", null, 50)));
        when(client.searchTopRecording("none", "artist-none"))
                .thenReturn(Optional.empty());

        final MusicBrainzBackfillCommand.BackfillSummary summary = command(repository, client).runBackfill(false, 30,
                90);

        assertThat(summary.processed()).isEqualTo(3);
        assertThat(summary.matched()).isEqualTo(1);
        assertThat(summary.lowScore()).isEqualTo(1);
        assertThat(summary.notFound()).isEqualTo(1);
        assertThat(summary.failed()).isZero();
        assertThat(summary.aborted()).isFalse();
        assertThat(matched.getMbId()).isEqualTo("mbid-ok");
        assertThat(matched.getMetadataSource()).isEqualTo(MetadataSource.EXTERNAL_API);
        verify(repository, times(1)).save(matched);
        verify(repository, never()).save(lowScore);
    }

    @Test
    @DisplayName("runBackfill: findMissingMbId selective query 결과만, batch 상한 적용")
    void runBackfill_usesSelectiveQueryAndBatchLimit() {
        final SongRepository repository = mock(SongRepository.class);
        when(repository.findMissingMbId()).thenReturn(List.of(seed("a"), seed("b"), seed("c")));
        final MusicBrainzClient client = mock(MusicBrainzClient.class);
        when(client.searchTopRecording(anyString(), anyString())).thenReturn(Optional.empty());

        final MusicBrainzBackfillCommand.BackfillSummary summary = command(repository, client).runBackfill(false, 2,
                90);

        assertThat(summary.processed()).isEqualTo(2);
        verify(repository).findMissingMbId();
    }

    @Test
    @DisplayName("runBackfill: dryRun 이면 save 호출 없이 적용 가능 후보만 집계")
    void runBackfill_dryRun_noPersist() {
        final Song target = seed("dry");
        final SongRepository repository = mock(SongRepository.class);
        when(repository.findMissingMbId()).thenReturn(List.of(target));
        when(repository.findByMbId(anyString())).thenReturn(Optional.empty());
        when(repository.findByIsrc(anyString())).thenReturn(Optional.empty());
        final MusicBrainzClient client = mock(MusicBrainzClient.class);
        when(client.searchTopRecording("dry", "artist-dry"))
                .thenReturn(Optional.of(new MusicBrainzMatch("mbid-dry", "KRA9", 99)));

        final MusicBrainzBackfillCommand.BackfillSummary summary = command(repository, client).runBackfill(true, 30,
                90);

        assertThat(summary.matched()).isEqualTo(1);
        assertThat(target.getMbId()).isNull();
        verify(repository, never()).save(any(Song.class));
    }

    @Test
    @DisplayName("runBackfill: 503 소진(MusicBrainzRateLimitException) 시 batch 즉시 중단 aborted=true")
    void runBackfill_rateLimit_abortsBatch() {
        final SongRepository repository = mock(SongRepository.class);
        when(repository.findMissingMbId()).thenReturn(List.of(seed("a"), seed("b")));
        final MusicBrainzClient client = mock(MusicBrainzClient.class);
        when(client.searchTopRecording("a", "artist-a"))
                .thenThrow(new MusicBrainzRateLimitException("503 exhausted"));

        final MusicBrainzBackfillCommand.BackfillSummary summary = command(repository, client).runBackfill(false, 30,
                90);

        assertThat(summary.aborted()).isTrue();
        assertThat(summary.processed()).isEqualTo(1);
        verify(client, never()).searchTopRecording("b", "artist-b");
    }

    @Test
    @DisplayName("runBackfill: 곡 단위 RuntimeException 은 격리, 다음 곡 진행 + failed 집계")
    void runBackfill_songError_isolated() {
        final Song failing = seed("fail");
        final Song ok = seed("ok");
        final SongRepository repository = mock(SongRepository.class);
        when(repository.findMissingMbId()).thenReturn(List.of(failing, ok));
        when(repository.findByMbId(anyString())).thenReturn(Optional.empty());
        when(repository.findByIsrc(anyString())).thenReturn(Optional.empty());
        final MusicBrainzClient client = mock(MusicBrainzClient.class);
        when(client.searchTopRecording("fail", "artist-fail"))
                .thenThrow(new RuntimeException("boom"));
        when(client.searchTopRecording("ok", "artist-ok"))
                .thenReturn(Optional.of(new MusicBrainzMatch("mbid-ok", "KRA1", 95)));

        final MusicBrainzBackfillCommand.BackfillSummary summary = command(repository, client).runBackfill(false, 30,
                90);

        assertThat(summary.processed()).isEqualTo(2);
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.matched()).isEqualTo(1);
        assertThat(summary.aborted()).isFalse();
        verify(repository).save(ok);
    }

    @Test
    @DisplayName("runBackfill: 같은 mbId 가 다른 곡에 이미 있으면 UNIQUE 충돌 회피 skip(failed)")
    void runBackfill_mbIdConflict_skips() {
        final Song target = seed("conflict");
        final Song other = seed("other");
        ReflectionTestUtils.setField(target, "id", 1L);
        ReflectionTestUtils.setField(other, "id", 2L);
        final SongRepository repository = mock(SongRepository.class);
        when(repository.findMissingMbId()).thenReturn(List.of(target));
        when(repository.findByMbId("mbid-dup")).thenReturn(Optional.of(other));
        final MusicBrainzClient client = mock(MusicBrainzClient.class);
        when(client.searchTopRecording("conflict", "artist-conflict"))
                .thenReturn(Optional.of(new MusicBrainzMatch("mbid-dup", "KRA1", 95)));

        final MusicBrainzBackfillCommand.BackfillSummary summary = command(repository, client).runBackfill(false, 30,
                90);

        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.matched()).isZero();
        assertThat(target.getMbId()).isNull();
        verify(repository, never()).save(any(Song.class));
    }

    @Test
    @DisplayName("resolveIsrc: 검색 응답에 ISRC 없으면 lookupIsrc 로 보강")
    void runBackfill_isrcLookupFallback() {
        final Song target = seed("lookup");
        final SongRepository repository = mock(SongRepository.class);
        when(repository.findMissingMbId()).thenReturn(List.of(target));
        when(repository.findByMbId(anyString())).thenReturn(Optional.empty());
        when(repository.findByIsrc(anyString())).thenReturn(Optional.empty());
        final MusicBrainzClient client = mock(MusicBrainzClient.class);
        when(client.searchTopRecording("lookup", "artist-lookup"))
                .thenReturn(Optional.of(new MusicBrainzMatch("mbid-l", null, 95)));
        when(client.lookupIsrc("mbid-l")).thenReturn(Optional.of("KR-LOOKUP"));

        command(repository, client).runBackfill(false, 30, 90);

        assertThat(target.getIsrc()).isEqualTo("KR-LOOKUP");
        verify(client).lookupIsrc("mbid-l");
    }
}
