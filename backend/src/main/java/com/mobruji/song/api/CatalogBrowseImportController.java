package com.mobruji.song.api;

import java.util.List;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mobruji.admin.AdminTokenVerifier;
import com.mobruji.song.api.dto.CatalogBrowseImportResponse;
import com.mobruji.song.application.catalogimport.CatalogBrowseImportCommand;
import com.mobruji.song.application.catalogimport.CatalogBrowseProperties;
import com.mobruji.song.application.catalogimport.KoreanArtistSeed;

import lombok.RequiredArgsConstructor;

/**
 * MusicBrainz browse 기반 카탈로그 대량 임포트 admin 트리거 — spec {@code song-catalog-expansion.md} (#1705).
 *
 * <p>{@code X-Admin-Token} 헤더 필수 (v0.3 P0 admin 게이트, #224/#228). 누락/불일치 → 401.
 * 한국 아티스트 시드({@link KoreanArtistSeed}) 를 입력으로 아티스트별 recording 을 browse 해 CC0 메타만 Song
 * 으로 upsert 한다. 외부 호출이 많아(1 req/s) 동기 응답이 길어질 수 있으므로 {@code maxArtists}/
 * {@code maxRecordingsPerArtist} 로 1회 분량을 통제하고 운영자가 단계적으로 상향한다.
 *
 * <p>{@code dryRun=true}(기본) 이면 DB write 없이 적재 가능 후보만 집계한다.
 */
@RestController
@RequestMapping("/api/v1/admin/songs")
@RequiredArgsConstructor
public class CatalogBrowseImportController {

    private final AdminTokenVerifier adminTokenVerifier;
    private final CatalogBrowseImportCommand importCommand;
    private final KoreanArtistSeed artistSeed;
    private final CatalogBrowseProperties properties;

    @PostMapping("/catalog-browse-import")
    public CatalogBrowseImportResponse trigger(
            @RequestHeader(value = "X-Admin-Token", required = false) final String adminToken,
            @RequestParam(name = "dryRun", defaultValue = "true") final boolean dryRun,
            @RequestParam(name = "maxArtists", required = false) final Integer maxArtists,
            @RequestParam(name = "maxRecordingsPerArtist", required = false) final Integer maxRecordingsPerArtist) {
        adminTokenVerifier.verify(adminToken);
        final List<String> artistNames = artistSeed.load();
        final int resolvedMaxArtists = maxArtists != null ? maxArtists : properties.maxArtists();
        final int resolvedMaxPerArtist = maxRecordingsPerArtist != null
                ? maxRecordingsPerArtist
                : properties.maxRecordingsPerArtist();
        return CatalogBrowseImportResponse.from(importCommand.runBrowseImport(
                artistNames, resolvedMaxArtists, resolvedMaxPerArtist, dryRun));
    }
}
