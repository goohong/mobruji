package com.mobruji.song.application.musicbrainz;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * MusicBrainz Recording 매칭 backfill 설정 — spec {@code musicbrainz-integration.md} §5-3-1.
 *
 * <p>{@link MusicBrainzClient} 가 호출(User-Agent/throttle/backoff/score)에, {@link MusicBrainzBackfillCommand}
 * 가 batch/score 임계에 본 properties 를 주입받는다. 모든 외부화 값은 환경변수 override 를 허용한다
 * ({@code application.yml} 참고). 필수 값은 {@link NotBlank}/{@link NotNull} 로 부트 fail-fast.
 *
 * <p>약관 준수 — MusicBrainz anonymous 호출은 (a) contact 포함 식별 {@code userAgent} 의무,
 * (b) 1 req/s rate limit ({@code throttle}), (c) 503 시 즉시 재시도 금지({@code backoff}) 를 강제한다.
 */
@Validated
@ConfigurationProperties(prefix = "musicbrainz")
public record MusicBrainzProperties(
        @NotBlank String baseUrl,
        @NotBlank String userAgent,
        @NotNull @DurationMin(millis = 1) Duration requestTimeout,
        @NotNull @DurationMin(nanos = 0) Duration throttle,
        @Min(0) int maxRetries,
        @NotNull @DurationMin(nanos = 0) Duration backoff,
        @Min(0) @Max(100) int minScore,
        @Min(1) int searchLimit,
        @Valid @NotNull Backfill backfill
) {

    /**
     * 정기/admin backfill batch 세부 설정.
     *
     * <ul>
     * <li>{@code batchSize}: 한 사이클 처리 곡 수 상한. 1.1s throttle × N = 최소 소요 시간.</li>
     * <li>{@code enabled}: 정기 스케줄러 활성 플래그 (기본 false — 로컬/CI 자동 호출 금지). admin endpoint 는 무관.</li>
     * </ul>
     */
    public record Backfill(
            @Min(1) int batchSize,
            boolean enabled
    ) {
    }
}
