package com.mobruji.voice.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.mobruji.voice.domain.VoiceRange;
import com.mobruji.voice.domain.VoiceRangeNotFoundException;
import com.mobruji.voice.domain.VoiceRangeSnapshot;
import com.mobruji.voice.domain.VoiceRangeSourceMethod;
import com.mobruji.voice.infrastructure.VoiceRangeRepository;
import com.mobruji.voice.infrastructure.VoiceRangeSnapshotRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 음역대 저장/조회 application service.
 *
 * <p>spec: {@code docs/features/voice-range-progress.md} §3 비기능 — snapshot insert 시
 * {@link #METRIC_SNAPSHOT_INSERTED} 카운터 +1. 본 클래스가 단일 진실 (observability-baseline §5-3 표 정합).
 *
 * <p>로깅 정책: sessionId 원문은 절대 노출하지 않는다 ({@code docs/ai-harness/04-security-policy.md}
 * 익명 세션 룰). 진단용으로 SHA-256 hex prefix({@value #SESSION_HASH_HEX_LENGTH}자)만 노출 —
 * {@code SeedDeriver.hashHex16} 와 동일한 패턴(prefix 만, 원문 복원 불가).
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class VoiceRangeService {

    /**
     * Micrometer counter 이름 — spec {@code docs/features/observability-baseline.md} §5-3 표 단일 진실.
     * snapshot insert 1건당 +1. 본 상수 외부 참조(통합 테스트/대시보드/알림 룰)는 본 상수를 우선 사용.
     */
    static final String METRIC_SNAPSHOT_INSERTED = "mobruji.voice.range.snapshot.inserted";

    /** sessionId 로그 노출 시 사용하는 SHA-256 hex prefix 길이. */
    private static final int SESSION_HASH_HEX_LENGTH = 12;

    private static final String HASH_ALGORITHM = "SHA-256";

    private final VoiceRangeRepository voiceRangeRepository;
    private final VoiceRangeSnapshotRepository voiceRangeSnapshotRepository;
    private final MeterRegistry meterRegistry;

    public VoiceRange createOrReplace(final CreateVoiceRangeCommand createVoiceRangeCommand) {
        final VoiceRange voiceRange;
        try {
            voiceRange = voiceRangeRepository
                    .findBySessionId(createVoiceRangeCommand.sessionId())
                    .map(existingVoiceRange -> {
                        existingVoiceRange.updateRange(
                                createVoiceRangeCommand.lowestNoteMidi(),
                                createVoiceRangeCommand.highestNoteMidi(),
                                createVoiceRangeCommand.sourceMethod());
                        return existingVoiceRange;
                    })
                    .orElseGet(() -> voiceRangeRepository.save(VoiceRange.create(
                            createVoiceRangeCommand.sessionId(),
                            createVoiceRangeCommand.lowestNoteMidi(),
                            createVoiceRangeCommand.highestNoteMidi(),
                            createVoiceRangeCommand.sourceMethod())));
        } catch (final IllegalArgumentException e) {
            log.warn(
                    "voice range validation failed: session_hash={} caller=createOrReplace reason={}",
                    hashSessionId(createVoiceRangeCommand.sessionId()), e.getMessage());
            throw e;
        }
        voiceRangeSnapshotRepository.save(VoiceRangeSnapshot.fromVoiceRange(voiceRange));
        recordSnapshotInserted(voiceRange.getSessionId(), voiceRange.getSourceMethod(), "createOrReplace");
        return voiceRange;
    }

    @Transactional(readOnly = true)
    public VoiceRange readBySessionId(final String sessionId) {
        return voiceRangeRepository.findBySessionId(sessionId)
                .orElseThrow(() -> {
                    log.warn("voice range not found: session_hash={}", hashSessionId(sessionId));
                    return new VoiceRangeNotFoundException(sessionId);
                });
    }

    @Transactional(readOnly = true)
    public List<VoiceRangeSnapshot> readHistoryBySessionId(final String sessionId) {
        return voiceRangeSnapshotRepository.findBySessionIdOrderByMeasuredAtAsc(sessionId);
    }

    public VoiceRange updateBySessionId(
            final String sessionId,
            final UpdateVoiceRangeCommand updateVoiceRangeCommand) {
        final VoiceRange voiceRange = voiceRangeRepository.findBySessionId(sessionId)
                .orElseThrow(() -> {
                    log.warn("voice range not found: session_hash={}", hashSessionId(sessionId));
                    return new VoiceRangeNotFoundException(sessionId);
                });
        try {
            voiceRange.updateRange(
                    updateVoiceRangeCommand.lowestNoteMidi(),
                    updateVoiceRangeCommand.highestNoteMidi(),
                    updateVoiceRangeCommand.sourceMethod());
        } catch (final IllegalArgumentException e) {
            log.warn(
                    "voice range validation failed: session_hash={} caller=updateBySessionId reason={}",
                    hashSessionId(sessionId), e.getMessage());
            throw e;
        }
        voiceRangeSnapshotRepository.save(VoiceRangeSnapshot.fromVoiceRange(voiceRange));
        recordSnapshotInserted(voiceRange.getSessionId(), voiceRange.getSourceMethod(), "updateBySessionId");
        return voiceRange;
    }

    /**
     * snapshot insert 직후 metric +1 + INFO 로그. 양 경로(createOrReplace / updateBySessionId)
     * 가 공유하는 단일 기록 지점으로, 향후 라벨/태그 추가 시 한 곳에서 변경한다.
     */
    private void recordSnapshotInserted(
            final String sessionId,
            final VoiceRangeSourceMethod sourceMethod,
            final String sourceMethodName) {
        Counter.builder(METRIC_SNAPSHOT_INSERTED)
                .description("voice_range_snapshot insert 1건 누적 (spec: voice-range-progress §3 비기능)")
                .register(meterRegistry)
                .increment();
        log.info(
                "event=voice.range.snapshot.inserted session_hash={} source={} caller={}",
                hashSessionId(sessionId), sourceMethod, sourceMethodName);
    }

    /**
     * sessionId 원문 노출 금지 ({@code 04-security-policy} 익명 세션 룰).
     * SHA-256 의 hex prefix {@value #SESSION_HASH_HEX_LENGTH}자만 반환 — 원문 복원 불가, 디버깅 식별자 용도.
     * 입력이 {@code null} 이면 "unknown" 을 반환해 로그 분기 분실을 방지한다.
     */
    private static String hashSessionId(final String sessionId) {
        if (sessionId == null) {
            return "unknown";
        }
        try {
            final MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            final byte[] hash = digest.digest(sessionId.getBytes(StandardCharsets.UTF_8));
            final String hex = HexFormat.of().formatHex(hash);
            return hex.substring(0, Math.min(SESSION_HASH_HEX_LENGTH, hex.length()));
        } catch (final NoSuchAlgorithmException e) {
            return "unhashable";
        }
    }
}
