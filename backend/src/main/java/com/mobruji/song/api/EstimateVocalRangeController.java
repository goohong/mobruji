package com.mobruji.song.api;

import java.util.OptionalInt;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mobruji.admin.AdminTokenVerifier;
import com.mobruji.song.api.dto.EstimateVocalRangeResponse;
import com.mobruji.song.application.SongVocalRangeEstimateCommand;

import lombok.RequiredArgsConstructor;

/**
 * 음역대 메타추정 on-demand admin 트리거 — 이슈 #1788. {@link SongAudioBackfillController} 패턴 미러.
 *
 * <p>{@link SongVocalRangeEstimateCommand} 는 {@code ApplicationArguments} 전용 부팅 trigger 라
 * ({@code ./gradlew bootRun --args='--mobruji.estimate-range=true'}) 도커 dev 컨테이너에서는 닿지 않는다.
 * 본 endpoint 가 동일 추정 로직({@link SongVocalRangeEstimateCommand#runEstimate(OptionalInt)})을 HTTP 로 노출해
 * dev 컨테이너에서도 음역대 미보유 곡을 메타 추정으로 채워 추천 풀에 진입시킨다.
 *
 * <p>{@code X-Admin-Token} 헤더 필수 (v0.3 P0 admin 게이트, #224/#228). 누락/불일치 → 401. 추정은 오디오
 * 다운로드 없이 즉시 산출되므로 동기 처리 후 집계를 그대로 응답한다. {@code limit=N} 으로 id 순 앞 N곡만 chunk
 * 처리해 반복 실행할 수 있다 (적용된 곡은 다음 호출 후보에서 자동 제외).
 */
@RestController
@RequestMapping("/api/v1/admin/songs")
@RequiredArgsConstructor
public class EstimateVocalRangeController {

    private final AdminTokenVerifier adminTokenVerifier;
    private final SongVocalRangeEstimateCommand estimateCommand;

    @PostMapping("/estimate-vocal-range")
    public EstimateVocalRangeResponse trigger(
            @RequestHeader(value = "X-Admin-Token", required = false) final String adminToken,
            @RequestParam(name = "limit", required = false) final Integer limit) {
        adminTokenVerifier.verify(adminToken);
        final OptionalInt resolvedLimit = limit != null && limit > 0
                ? OptionalInt.of(limit)
                : OptionalInt.empty();
        return EstimateVocalRangeResponse.from(estimateCommand.runEstimate(resolvedLimit));
    }
}
