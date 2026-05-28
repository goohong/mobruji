package com.mobruji.user.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.mobruji.user.domain.AnonymousSession;
import com.mobruji.user.domain.RevokedReason;
import com.mobruji.user.infrastructure.AnonymousSessionRepository;

/**
 * {@link SessionAuthGuard} 단위 테스트 — session-bound endpoint 인증 매트릭스
 * (rev 16 / #238) + 만료/revoke 게이트 (PR 3, #924, spec §5-2).
 */
class SessionAuthGuardTest {

    private static final String VALID_SESSION_ID = "00000000-0000-4000-8000-00000000000a";
    private static final String OTHER_VALID_SESSION_ID = "00000000-0000-4000-8000-00000000000b";

    private AnonymousSessionRepository anonymousSessionRepository;
    private SessionActivityTracker sessionActivityTracker;
    private SessionAuthGuard sessionAuthGuard;

    @BeforeEach
    void setUp() {
        anonymousSessionRepository = Mockito.mock(AnonymousSessionRepository.class);
        sessionActivityTracker = Mockito.mock(SessionActivityTracker.class);
        final AnonymousSessionProperties properties = new AnonymousSessionProperties(
                AnonymousSessionProperties.DEFAULT_TTL_DAYS,
                null,
                null,
                null,
                null);
        sessionAuthGuard = new SessionAuthGuard(
                anonymousSessionRepository, sessionActivityTracker, properties);
        // default — 행 없음 (bootstrap 옵션 a, 통과).
        given(anonymousSessionRepository.findById(any())).willReturn(Optional.empty());
    }

    @Test
    @DisplayName("path 와 헤더가 일치하면 (행 없음 = bootstrap 통과) 예외 없음")
    void verify_matching_passes() {
        assertThatCode(() -> sessionAuthGuard.verify(VALID_SESSION_ID, VALID_SESSION_ID))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("헤더 누락(null) → 401")
    void verify_nullHeader_throws401() {
        assertThatThrownBy(() -> sessionAuthGuard.verify(VALID_SESSION_ID, null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(throwable -> ((ResponseStatusException) throwable).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("헤더 blank → 401")
    void verify_blankHeader_throws401() {
        assertThatThrownBy(() -> sessionAuthGuard.verify(VALID_SESSION_ID, "   "))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(throwable -> ((ResponseStatusException) throwable).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("path 와 헤더 값 불일치 → 401 (다른 세션 히스토리 노출 차단)")
    void verify_mismatch_throws401() {
        assertThatThrownBy(() -> sessionAuthGuard.verify(VALID_SESSION_ID, OTHER_VALID_SESSION_ID))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(throwable -> ((ResponseStatusException) throwable).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("path 자체가 null/blank 인 비정상 호출도 → 401 (방어적)")
    void verify_blankPath_throws401() {
        assertThatThrownBy(() -> sessionAuthGuard.verify(null, VALID_SESSION_ID))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(throwable -> ((ResponseStatusException) throwable).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThatThrownBy(() -> sessionAuthGuard.verify("", VALID_SESSION_ID))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(throwable -> ((ResponseStatusException) throwable).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("UTF-8 한글 sessionId → 401 (UUIDv4 형식 가드, ADR-0011 client 발급 전제)")
    void verify_utf8_nonUuid_throws401() {
        // 이전 fixture 는 hex 외 문자 통과 가정이었으나 ADR-0011 / PR #991 client UUIDv4
        // 발급 전제로 형식 가드가 강제되면서 임의 문자열은 401 로 차단된다.
        assertThatThrownBy(() -> sessionAuthGuard.verify("세션-가나다", "세션-가나다"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(throwable -> ((ResponseStatusException) throwable).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(sessionActivityTracker, never()).markActive(any());
    }

    @Test
    @DisplayName("legacy `sess_<ts>_<rand>` 형식 sessionId → 401 (회귀 사고 박제 — directive 1508668399423590541)")
    void verify_legacyFallbackFormat_throws401() {
        // production journal evidence (2026-05-28 08:27): stale localStorage 의
        // `sess_mpi2bqkh_jpnmqpag` 가 recommend POST 까지 도달해 @Pattern 400 발생.
        // SessionAuthGuard 형식 가드 추가로 voice-range GET 진입 시점에 401 로 차단 →
        // recommend POST 로 도달 자체가 불가능. FE 의 ensureSessionId rotate funnel 진입.
        assertThatThrownBy(() -> sessionAuthGuard.verify("sess_mpi2bqkh_jpnmqpag", "sess_mpi2bqkh_jpnmqpag"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(throwable -> ((ResponseStatusException) throwable).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(sessionActivityTracker, never()).markActive(any());
    }

    @Test
    @DisplayName("대문자 hex UUIDv4 형식 sessionId → 401 (소문자 hex only)")
    void verify_uppercaseHex_throws401() {
        // SessionIdPatterns.UUID_V4 는 소문자 hex 만 매치. 대문자 발급 generator (legacy 일부) 도 차단.
        assertThatThrownBy(() -> sessionAuthGuard.verify(
                "00000000-0000-4000-8000-00000000000A",
                "00000000-0000-4000-8000-00000000000A"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(throwable -> ((ResponseStatusException) throwable).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("UUIDv4 너무 짧음 → 401")
    void verify_truncatedUuid_throws401() {
        assertThatThrownBy(() -> sessionAuthGuard.verify(
                "0000-0000-4000-8000-00000000000a",
                "0000-0000-4000-8000-00000000000a"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(throwable -> ((ResponseStatusException) throwable).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("AnonymousSession 행 없음 — bootstrap (a) 옵션으로 통과, tracker 호출 안 함")
    void verify_noRow_passes_withoutTracker() {
        sessionAuthGuard.verify(VALID_SESSION_ID, VALID_SESSION_ID);

        verify(sessionActivityTracker, never()).markActive(any());
    }

    @Test
    @DisplayName("revokedAt != null → 401 (회전한 사용자가 옛 sessionId 로 호출 차단, #924 핵심)")
    void verify_revoked_throws401() {
        final AnonymousSession revoked = AnonymousSession.create(VALID_SESSION_ID);
        revoked.revoke(RevokedReason.USER_ROTATE);
        given(anonymousSessionRepository.findById(VALID_SESSION_ID)).willReturn(Optional.of(revoked));

        assertThatThrownBy(() -> sessionAuthGuard.verify(VALID_SESSION_ID, VALID_SESSION_ID))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(throwable -> ((ResponseStatusException) throwable).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(sessionActivityTracker, never()).markActive(any());
    }

    @Test
    @DisplayName("lastSeenAt + TTL < now → 401 (sliding TTL 만료)")
    void verify_expired_throws401() {
        // ttl-days default 180일. lastSeenAt = 181일 전 → 만료.
        final AnonymousSession expired = expiredSession();
        given(anonymousSessionRepository.findById(VALID_SESSION_ID)).willReturn(Optional.of(expired));

        assertThatThrownBy(() -> sessionAuthGuard.verify(VALID_SESSION_ID, VALID_SESSION_ID))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(throwable -> ((ResponseStatusException) throwable).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(sessionActivityTracker, never()).markActive(any());
    }

    @Test
    @DisplayName("OK (행 존재 + 미revoke + 미만료) → 통과 + tracker.markActive 호출")
    void verify_active_passes_andCallsTracker() {
        final AnonymousSession active = AnonymousSession.create(VALID_SESSION_ID);
        given(anonymousSessionRepository.findById(VALID_SESSION_ID)).willReturn(Optional.of(active));

        sessionAuthGuard.verify(VALID_SESSION_ID, VALID_SESSION_ID);

        verify(sessionActivityTracker, times(1)).markActive(VALID_SESSION_ID);
    }

    private static AnonymousSession expiredSession() {
        // sliding TTL 만료 시뮬레이션 — 200일 전 lastSeenAt 으로 reflection 없이 생성.
        // AnonymousSession 의 public ctor 가 PACKAGE 라 같은 패키지 helper 가 아니면 reflection 필요.
        // 여기서는 Mockito spy 로 lastSeenAt 만 stub.
        final AnonymousSession session = AnonymousSession.create(VALID_SESSION_ID);
        final AnonymousSession spy = Mockito.spy(session);
        given(spy.getLastSeenAt()).willReturn(LocalDateTime.now().minusDays(200));
        return spy;
    }
}
