package com.mobruji.user.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 익명 sessionId 라이프사이클 정책 설정.
 *
 * <p>spec: {@code docs/features/anonymous-session-lifecycle.md} §5-8, ADR-0013.
 *
 * <ul>
 * <li>{@code ttlDays}: sliding TTL (default 180일 — ADR-0013 §D-1).</li>
 * <li>{@code cleanupCron}: 만료 batch cron 표현식 (Spring 6-field, KST).</li>
 * <li>{@code cleanupMaxPerRun}: batch 1회당 최대 처리 sessionId 수 (부하 분산).</li>
 * </ul>
 *
 * <p>모두 환경변수로 override 가능. 미지정 시 코드 default fallback — webhook URL 같은
 * fail-fast 정책 미적용 (spec §3 비기능 "설정 외부화" 단서: 정말 중요한 것만 부트 fail-fast).
 */
@ConfigurationProperties(prefix = "mobruji.session")
public record AnonymousSessionProperties(
        Integer ttlDays,
        String cleanupCron,
        Integer cleanupMaxPerRun
) {

    /** ADR-0013 §D-1 default — 180일 inactive 만료. */
    public static final int DEFAULT_TTL_DAYS = 180;

    /** spec §5-8 default — 매일 KST 04:00. */
    public static final String DEFAULT_CLEANUP_CRON = "0 0 4 * * *";

    /** spec §3 기능 — batch 1회 10,000 sessionId 상한. */
    public static final int DEFAULT_CLEANUP_MAX_PER_RUN = 10_000;

    public AnonymousSessionProperties {
        // null 인 항목은 default fallback (record canonical constructor 보정).
        if (ttlDays == null) {
            ttlDays = DEFAULT_TTL_DAYS;
        }
        if (cleanupCron == null || cleanupCron.isBlank()) {
            cleanupCron = DEFAULT_CLEANUP_CRON;
        }
        if (cleanupMaxPerRun == null) {
            cleanupMaxPerRun = DEFAULT_CLEANUP_MAX_PER_RUN;
        }
        if (ttlDays <= 0) {
            throw new IllegalArgumentException("mobruji.session.ttl-days must be > 0: " + ttlDays);
        }
        if (cleanupMaxPerRun <= 0) {
            throw new IllegalArgumentException(
                    "mobruji.session.cleanup-max-per-run must be > 0: " + cleanupMaxPerRun);
        }
    }
}
