package com.mobruji.song.api;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mobruji.admin.AdminTokenVerifier;
import com.mobruji.song.api.dto.MusicBrainzBackfillResponse;
import com.mobruji.song.application.musicbrainz.MusicBrainzBackfillCommand;
import com.mobruji.song.application.musicbrainz.MusicBrainzProperties;

import lombok.RequiredArgsConstructor;

/**
 * MusicBrainz backfill admin 트리거 — spec {@code musicbrainz-integration.md} §5-2.
 *
 * <p>{@code X-Admin-Token} 헤더 필수 (v0.3 P0 admin 게이트, #224/#228). 누락/불일치 → 401.
 * 정기 스케줄러({@link com.mobruji.song.application.musicbrainz.MusicBrainzBackfillScheduledJob}) 와 별개로
 * 운영자가 즉시 backfill 을 돌리거나({@code dryRun=false}) 매칭 후보만 점검({@code dryRun=true}, 기본)할 때 쓴다.
 *
 * <p>rate limit(1 req/s) 때문에 동기 응답이 batch 곡 수 × ~1.1s 만큼 걸린다 — {@code maxBatch} 로 1회 분량을 제어.
 */
@RestController
@RequestMapping("/api/v1/admin/songs")
@RequiredArgsConstructor
public class MusicBrainzBackfillController {

    private final AdminTokenVerifier adminTokenVerifier;
    private final MusicBrainzBackfillCommand backfillCommand;
    private final MusicBrainzProperties properties;

    @PostMapping("/musicbrainz-backfill")
    public MusicBrainzBackfillResponse trigger(
            @RequestHeader(value = "X-Admin-Token", required = false) final String adminToken,
            @RequestParam(name = "dryRun", defaultValue = "true") final boolean dryRun,
            @RequestParam(name = "maxBatch", required = false) final Integer maxBatch,
            @RequestParam(name = "minScore", required = false) final Integer minScore) {
        adminTokenVerifier.verify(adminToken);
        final int resolvedMaxBatch = maxBatch != null ? maxBatch : properties.backfill().batchSize();
        final int resolvedMinScore = minScore != null ? minScore : properties.minScore();
        return MusicBrainzBackfillResponse.from(
                backfillCommand.runBackfill(dryRun, resolvedMaxBatch, resolvedMinScore));
    }
}
