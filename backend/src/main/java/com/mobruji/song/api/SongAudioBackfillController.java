package com.mobruji.song.api;

import java.util.OptionalInt;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mobruji.admin.AdminTokenVerifier;
import com.mobruji.song.api.dto.AudioBackfillResponse;
import com.mobruji.song.application.AudioBackfillOnDemandService;

import lombok.RequiredArgsConstructor;

/**
 * audio backfill on-demand admin 트리거 — 이슈 #1757. {@link MusicBrainzBackfillController} 패턴 미러.
 *
 * <p>{@code X-Admin-Token} 헤더 필수 (v0.3 P0 admin 게이트, #224/#228). 누락/불일치 → 401. 정기 스케줄러
 * ({@link com.mobruji.song.application.AudioAnalysisScheduledBackfill}, 주1회) 와 별개로 운영자가 즉시 음역대
 * 미보유 곡을 YouTube ytsearch 자동매칭 + 자체분석으로 채워 추천 풀에 진입시킨다.
 *
 * <p>곡당 분석이 수 분 걸리므로 {@code dryRun=false} 는 비동기로 backfill 을 시작하고 선택 집계만 즉시 응답한다.
 * {@code dryRun=true}(기본) 는 분석/적용 없이 후보 집계만 미리 본다.
 */
@RestController
@RequestMapping("/api/v1/admin/songs")
@RequiredArgsConstructor
public class SongAudioBackfillController {

    private final AdminTokenVerifier adminTokenVerifier;
    private final AudioBackfillOnDemandService backfillService;

    @PostMapping("/audio-backfill")
    public AudioBackfillResponse trigger(
            @RequestHeader(value = "X-Admin-Token", required = false) final String adminToken,
            @RequestParam(name = "target", defaultValue = AudioBackfillOnDemandService.TARGET_MISSING_RANGE) final String target,
            @RequestParam(name = "limit", required = false) final Integer limit,
            @RequestParam(name = "dryRun", defaultValue = "true") final boolean dryRun) {
        adminTokenVerifier.verify(adminToken);
        final OptionalInt resolvedLimit = limit != null && limit > 0
                ? OptionalInt.of(limit)
                : OptionalInt.empty();
        return AudioBackfillResponse.from(backfillService.trigger(target, resolvedLimit, dryRun));
    }
}
