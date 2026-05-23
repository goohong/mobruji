package com.mobruji.song.application.albumcover;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;

import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

/**
 * {@link AlbumCoverScheduledBackfill} 단위 테스트. selective query 결과를 그대로 위임하는지 검증.
 */
@SuppressWarnings("unchecked")
class AlbumCoverScheduledBackfillTest {

    private static Song seed(final String title) {
        return Song.builder()
                .title(title).artist("artist-" + title)
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
    }

    @Test
    @DisplayName("runScheduledBackfill: selective query 결과를 그대로 backfillCommand 에 위임")
    void runScheduledBackfill_delegatesQueryResult() {
        final Song s1 = seed("a");
        final Song s2 = seed("b");
        final SongRepository repository = mock(SongRepository.class);
        when(repository.findMissingAlbumCover()).thenReturn(List.of(s1, s2));
        final AlbumCoverBackfillCommand backfillCommand = mock(AlbumCoverBackfillCommand.class);
        when(backfillCommand.runBackfill(any(List.class)))
                .thenReturn(new AlbumCoverBackfillCommand.BackfillSummary(2, 1, 1, 1));

        final AlbumCoverScheduledBackfill scheduler = new AlbumCoverScheduledBackfill(repository, backfillCommand);
        scheduler.runScheduledBackfill();

        verify(backfillCommand).runBackfill(List.of(s1, s2));
    }

    @Test
    @DisplayName("runScheduledBackfill: 대상 0건이면 backfillCommand 호출 없이 종료")
    void runScheduledBackfill_noTargets_skips() {
        final SongRepository repository = mock(SongRepository.class);
        when(repository.findMissingAlbumCover()).thenReturn(List.of());
        final AlbumCoverBackfillCommand backfillCommand = mock(AlbumCoverBackfillCommand.class);

        final AlbumCoverScheduledBackfill scheduler = new AlbumCoverScheduledBackfill(repository, backfillCommand);
        scheduler.runScheduledBackfill();

        verify(backfillCommand, never()).runBackfill(any(List.class));
    }

    @Test
    @DisplayName("selectTargets: SongRepository#findMissingAlbumCover 를 그대로 위임 호출")
    void selectTargets_delegatesToRepository() {
        final Song s1 = seed("only");
        final SongRepository repository = mock(SongRepository.class);
        when(repository.findMissingAlbumCover()).thenReturn(List.of(s1));

        final AlbumCoverScheduledBackfill scheduler = new AlbumCoverScheduledBackfill(
                repository, mock(AlbumCoverBackfillCommand.class));

        final List<Song> targets = scheduler.selectTargets();
        assertThat(targets).containsExactly(s1);
        verify(repository).findMissingAlbumCover();
    }

    // ───────────────────── 메타데이터 회귀 가드 ─────────────────────
    // 운영 안전(@Profile)/결정성(cron+zone) 메타데이터가 실수로 바뀌면 외부 API 호출 폭주 또는 audio batch 충돌이
    // 발생하므로 reflection 으로 고정한다.

    @Test
    @DisplayName("@Profile 가 prod 로 고정되어야 한다 (local/test 활성화 회귀 가드)")
    void classProfile_isProdOnly() {
        final Profile profile = AlbumCoverScheduledBackfill.class.getAnnotation(Profile.class);
        assertThat(profile).as("@Profile 어노테이션이 존재해야 한다").isNotNull();
        assertThat(profile.value()).containsExactly("prod");
    }

    @Test
    @DisplayName("runScheduledBackfill: @Scheduled cron/zone 가 audio batch 30분 후 SUN/KST 로 고정 (회귀 가드)")
    void runScheduledBackfill_scheduledCronAndZoneFixed() throws NoSuchMethodException {
        final Method method = AlbumCoverScheduledBackfill.class.getDeclaredMethod("runScheduledBackfill");
        final Scheduled scheduled = method.getAnnotation(Scheduled.class);
        assertThat(scheduled).as("@Scheduled 어노테이션이 존재해야 한다").isNotNull();
        // audio analysis batch 가 04:00 KST 에 도는 것과 30분 간격을 유지해야 외부 API 부하 분산이 깨지지 않는다.
        assertThat(scheduled.cron()).isEqualTo("0 30 4 * * SUN");
        assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
    }
}
