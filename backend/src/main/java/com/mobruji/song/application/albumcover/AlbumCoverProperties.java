package com.mobruji.song.application.albumcover;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 곡 앨범 커버 backfill 설정 — 이슈 #322. 1차 출처 iTunes Search API.
 *
 * <p>{@link com.mobruji.song.application.albumcover.ItunesAlbumCoverClient} 가 본 properties 를 주입받아
 * 외부 호출에 사용한다. 후속 사이클에서 MusicBrainz/Spotify 출처가 추가되면 본 properties 에 nested
 * 항목으로 확장한다.
 *
 * <p>설계 — 모든 외부화 가능 값은 환경변수 override 를 허용한다 ({@code application.yml} 참고).
 * 부트 fail-fast 를 위해 필수 값은 {@link NotBlank}/{@link NotNull} 로 강제한다.
 */
@Validated
@ConfigurationProperties(prefix = "album-cover")
public record AlbumCoverProperties(
        @Valid @NotNull Itunes itunes
) {

    /**
     * iTunes Search API 호출 세부 설정.
     *
     * <ul>
     * <li>{@code baseUrl}: 검색 엔드포인트 (`https://itunes.apple.com/search`). 테스트에서 stub server 주소로 override.</li>
     * <li>{@code country}: country code (예: `KR`). 한국 가요 매칭 정확도 ↑.</li>
     * <li>{@code requestTimeout}: HTTP 호출 timeout. 곡당 권장 5s.</li>
     * <li>{@code throttle}: 곡 사이 sleep — Apple rate limit 명시 없으나 보수적 1 req/sec.</li>
     * <li>{@code thumbResolution}: {@code artworkUrl100} 의 `100x100` 을 치환할 해상도. 기본 `600x600`.</li>
     * </ul>
     */
    public record Itunes(
            @NotBlank String baseUrl,
            @NotBlank String country,
            @NotNull @DurationMin(millis = 1) Duration requestTimeout,
            @NotNull @DurationMin(nanos = 0) Duration throttle,
            @NotBlank String thumbResolution
    ) {
    }
}
