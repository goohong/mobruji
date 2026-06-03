package com.mobruji.song.application.catalogimport;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 곡 카탈로그 메타-only 임포트 설정 — spec {@code song-catalog-expansion.md} PR 2 (#1496).
 *
 * <p>{@link MusicBrainzSongMetadataLookupClient} 가 본 properties 를 주입받아 외부 호출에 사용하고,
 * {@link MetadataOnlyImportCommand} 가 throttle/importConfidence 를 참조한다.
 *
 * <p>출처는 MusicBrainz(CC0) 단독 — 영구 저장 가능한 메타데이터만 채운다. 음역대/key/tempo 는
 * 외부 출처가 제공하지 않으므로 채우지 않고, 자체 분석({@code song-self-analysis-pipeline.md}, #1490)
 * 큐가 후속한다 (spec §5-0).
 *
 * <p>약관 준수 — MusicBrainz anonymous 호출은 1 req/s rate limit + contact 포함 식별 User-Agent 를
 * 의무화한다 ({@code throttle} / {@code userAgent}).
 */
@Validated
@ConfigurationProperties(prefix = "catalog-import")
public record CatalogImportProperties(
        @NotBlank String musicBrainzBaseUrl,
        @NotBlank String userAgent,
        @NotNull @DurationMin(millis = 1) Duration requestTimeout,
        @NotNull @DurationMin(nanos = 0) Duration throttle,
        @DecimalMin("0.0") @DecimalMax("1.0") double importConfidence
) {
}
