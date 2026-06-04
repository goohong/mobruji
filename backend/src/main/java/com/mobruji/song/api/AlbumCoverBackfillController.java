package com.mobruji.song.api;

import java.util.OptionalInt;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mobruji.admin.AdminTokenVerifier;
import com.mobruji.song.api.dto.AlbumCoverBackfillResponse;
import com.mobruji.song.application.albumcover.AlbumCoverBackfillOnDemandService;

import lombok.RequiredArgsConstructor;

/**
 * album cover backfill on-demand admin 트리거 — 이슈 #1766. audio backfill
 * ({@link SongAudioBackfillController}, #1757) 패턴 미러.
 *
 * <p>{@code X-Admin-Token} 헤더 필수 (v0.3 P0 admin 게이트, #224/#228). 누락/불일치 → 401. 정기 스케줄러
 * ({@link com.mobruji.song.application.albumcover.AlbumCoverScheduledBackfill}, 주1회 일 04:30) 와 별개로 운영자가
 * 즉시 커버 미보유 곡을 iTunes/Cover Art Archive lookup 으로 채워 UX 표시를 보강한다.
 *
 * <p>곡당 lookup + throttle sleep 이 누적되므로 {@code dryRun=false} 는 비동기로 backfill 을 시작하고 선택 집계만
 * 즉시 응답한다. {@code dryRun=true}(기본) 는 lookup/적용 없이 후보 집계만 미리 본다.
 */
@RestController
@RequestMapping("/api/v1/admin/songs")
@RequiredArgsConstructor
public class AlbumCoverBackfillController {

    private final AdminTokenVerifier adminTokenVerifier;
    private final AlbumCoverBackfillOnDemandService backfillService;

    @PostMapping("/album-cover-backfill")
    public AlbumCoverBackfillResponse trigger(
            @RequestHeader(value = "X-Admin-Token", required = false) final String adminToken,
            @RequestParam(name = "limit", required = false) final Integer limit,
            @RequestParam(name = "dryRun", defaultValue = "true") final boolean dryRun) {
        adminTokenVerifier.verify(adminToken);
        final OptionalInt resolvedLimit = limit != null && limit > 0
                ? OptionalInt.of(limit)
                : OptionalInt.empty();
        return AlbumCoverBackfillResponse.from(backfillService.trigger(resolvedLimit, dryRun));
    }
}
