package com.mobruji.song.application.albumcover;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

/**
 * 곡 albumCoverUrl backfill batch — 이슈 #322 PR B.
 *
 * <p>출처는 {@link AlbumCoverLookupClient} 추상으로 분리되어 있다. 본 PR 에서는 {@link ItunesAlbumCoverClient}
 * 가 1차 출처. 후속 사이클 (MusicBrainz/Spotify) 에서는 lookup 우선순위 chain 으로 확장한다.
 *
 * <p>실행 방법 (수동 trigger, 운영 안전):
 * <pre>
 * ./gradlew bootRun --args='--spring.profiles.active=local --mobruji.backfill-album-cover=true'
 * </pre>
 *
 * <p>인자 미지정 시 no-op — 평시 부팅에 영향 없음. {@code test} 프로파일은 Spring Bean 자체 미등록으로 통합 테스트
 * 영향 없음.
 *
 * <p>동작:
 * <ol>
 * <li>{@link SongRepository#findMissingAlbumCover()} 로 albumCoverUrl 이 null 인 곡만 selective 조회.</li>
 * <li>각 곡에 대해 {@link AlbumCoverLookupClient#lookupAlbumCoverUrl(String, String)} 호출.</li>
 * <li>매칭 성공 시 {@link Song#backfillAlbumCoverUrl(String)} 위임, save.</li>
 * <li>매칭 실패는 통계만 +1, 다음 곡으로 진행 (graceful — 외부 API 의존이라 실패 정상).</li>
 * <li>곡 사이 properties.throttle() 만큼 sleep — Apple rate limit 보수적 보호.</li>
 * <li>완료 시 요약 로그 1줄 + 마지막 실행 시각 기록.</li>
 * </ol>
 *
 * <p>결정성 영향 없음 — 추천 알고리즘 입력과 무관, UX 표시 전용 (ADR 0010 정합).
 */
@Component
@Profile("!test")
public class AlbumCoverBackfillCommand implements ApplicationRunner {

    /** ApplicationArguments 에서 인식할 옵션 키. {@code --mobruji.backfill-album-cover=true} */
    static final String OPTION_KEY = "mobruji.backfill-album-cover";

    private static final Logger LOG = LoggerFactory.getLogger(AlbumCoverBackfillCommand.class);

    /**
     * 마지막 backfill 완료 시각 — in-memory atomic. 재기동 시 null.
     * SongStats API 또는 운영 가시성 화면에서 사용 가능. (현재는 로깅 위주.)
     */
    private static final AtomicReference<Instant> LAST_BACKFILL_COMPLETED_AT = new AtomicReference<>();

    private final SongRepository songRepository;
    private final AlbumCoverLookupClient lookupClient;
    private final AlbumCoverProperties properties;

    public AlbumCoverBackfillCommand(
            final SongRepository songRepository,
            final AlbumCoverLookupClient lookupClient,
            final AlbumCoverProperties properties) {
        this.songRepository = songRepository;
        this.lookupClient = lookupClient;
        this.properties = properties;
    }

    @Override
    public void run(final ApplicationArguments args) {
        if (!isOptionTrue(args)) {
            return;
        }
        runBackfill();
    }

    /**
     * 테스트/CLI/Scheduler 재사용 진입점. albumCoverUrl=null 곡만 selective 처리.
     *
     * @return 처리 요약
     */
    BackfillSummary runBackfill() {
        return runBackfill(songRepository.findMissingAlbumCover());
    }

    /**
     * 임의 곡 집합에 대해 backfill 실행 — Scheduled batch 가 같은 selective query 결과를 그대로 위임할 수 있도록
     * package-private 으로 노출.
     */
    BackfillSummary runBackfill(final List<Song> songs) {
        int analyzed = 0;
        int matched = 0;
        int updated = 0;
        int missed = 0;
        final Duration throttle = properties.itunes().throttle();
        for (final Song song : songs) {
            Objects.requireNonNull(song, "song must not be null");
            analyzed++;
            final Optional<String> coverUrl;
            try {
                coverUrl = lookupClient.lookupAlbumCoverUrl(song.getTitle(), song.getArtist());
            } catch (final RuntimeException e) {
                // 클라이언트가 graceful 보장하지만 방어. 곡 단위 격리.
                LOG.warn(
                        "album cover lookup error songId={} reason={}",
                        song.getId(), e.getMessage());
                missed++;
                throttle(throttle);
                continue;
            }
            if (coverUrl.isEmpty()) {
                missed++;
                throttle(throttle);
                continue;
            }
            matched++;
            final boolean changed;
            try {
                changed = song.backfillAlbumCoverUrl(coverUrl.get());
            } catch (final RuntimeException e) {
                LOG.warn(
                        "album cover apply failed songId={} reason={}",
                        song.getId(), e.getMessage());
                throttle(throttle);
                continue;
            }
            if (changed) {
                songRepository.save(song);
                updated++;
            }
            throttle(throttle);
        }
        LOG.info(
                "album cover backfill done analyzed={} matched={} updated={} missed={}",
                analyzed, matched, updated, missed);
        LAST_BACKFILL_COMPLETED_AT.set(Instant.now());
        return new BackfillSummary(analyzed, matched, updated, missed);
    }

    /**
     * 마지막 backfill 완료 시각 — 아직 한 번도 실행되지 않았으면 {@code null}. 재기동 시 초기화된다.
     */
    public static Instant getLastBackfillCompletedAt() {
        return LAST_BACKFILL_COMPLETED_AT.get();
    }

    private static void throttle(final Duration throttle) {
        if (throttle == null || throttle.isZero() || throttle.isNegative()) {
            return;
        }
        try {
            TimeUnit.MILLISECONDS.sleep(throttle.toMillis());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isOptionTrue(final ApplicationArguments args) {
        if (args == null || !args.containsOption(OPTION_KEY)) {
            return false;
        }
        final List<String> values = args.getOptionValues(OPTION_KEY);
        if (values == null || values.isEmpty()) {
            return true;
        }
        return "true".equalsIgnoreCase(values.get(values.size() - 1));
    }

    record BackfillSummary(
            int analyzed,
            int matched,
            int updated,
            int missed
    ) {
    }
}
