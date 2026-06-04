package com.mobruji.song.application.catalogimport;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * MusicBrainz browse 기반 곡 카탈로그 대량 임포트 설정 — spec {@code song-catalog-expansion.md} (#1705).
 *
 * <p>{@link MusicBrainzBrowseClient} 가 외부 호출(base-url/user-agent/timeout/throttle/backoff)에 사용하고,
 * {@link CatalogBrowseImportCommand} 가 페이징/규모 상한(page-size / max-artists / max-recordings-per-artist)과
 * {@code import-confidence} 를 참조한다.
 *
 * <p>출처는 MusicBrainz(CC0) 단독 — 영구 저장 가능한 메타데이터만 채운다. 음역대/key/tempo 는 외부 출처가
 * 제공하지 않으므로 채우지 않고, 자체 분석({@code song-self-analysis-pipeline.md}) 큐가 후속한다 (spec §5-0).
 *
 * <p>약관 준수 — MusicBrainz anonymous 호출은 1 req/s rate limit({@code throttle}) + contact 포함 식별
 * User-Agent({@code userAgent}) 를 의무화하고, 503 응답 시 즉시 재시도 금지({@code maxRetries}/{@code backoff} 지수 backoff).
 */
@Validated
@ConfigurationProperties(prefix = "catalog-browse")
public record CatalogBrowseProperties(
        @NotBlank String musicBrainzBaseUrl,
        @NotBlank String userAgent,
        @NotNull @DurationMin(millis = 1) Duration requestTimeout,
        @NotNull @DurationMin(nanos = 0) Duration throttle,
        @NotNull @DurationMin(nanos = 0) Duration backoff,
        @Min(0) int maxRetries,
        @Min(1) int pageSize,
        @Min(1) int maxArtists,
        @Min(1) int maxRecordingsPerArtist,
        @DecimalMin("0.0") @DecimalMax("1.0") double importConfidence
) {
}
