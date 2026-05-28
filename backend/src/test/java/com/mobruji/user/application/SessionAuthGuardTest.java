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
        assertThatCode(() -> sessionAuthGuard.verify("session-A", "session-A"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("헤더 누락(null) → 401")
    void verify_nullHeader_throws401() {
        assertThatThrownBy(() -> sessionAuthGuard.verify("session-A", null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(throwable -> ((ResponseStatusException) throwable).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("헤더 blank → 401")
    void verify_blankHeader_throws401() {
        assertThatThrownBy(() -> sessionAuthGuard.verify("session-A", "   "))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(throwable -> ((ResponseStatusException) throwable).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("path 와 헤더 값 불일치 → 401 (다른 세션 히스토리 노출 차단)")
    void verify_mismatch_throws401() {
        assertThatThrownBy(() -> sessionAuthGuard.verify("session-A", "session-B"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(throwable -> ((ResponseStatusException) throwable).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("path 자체가 null/blank 인 비정상 호출도 → 401 (방어적)")
    void verify_blankPath_throws401() {
        assertThatThrownBy(() -> sessionAuthGuard.verify(null, "session-A"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(throwable -> ((ResponseStatusException) throwable).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThatThrownBy(() -> sessionAuthGuard.verify("", "session-A"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(throwable -> ((ResponseStatusException) throwable).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("UTF-8 한글 sessionId 도 정상 비교")
    void verify_utf8_matching_passes() {
        assertThatCode(() -> sessionAuthGuard.verify("세션-가나다", "세션-가나다"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AnonymousSession 행 없음 — bootstrap (a) 옵션으로 통과, tracker 호출 안 함")
    void verify_noRow_passes_withoutTracker() {
        sessionAuthGuard.verify("session-A", "session-A");

        verify(sessionActivityTracker, never()).markActive(any());
    }

    @Test
    @DisplayName("revokedAt != null → 401 (회전한 사용자가 옛 sessionId 로 호출 차단, #924 핵심)")
    void verify_revoked_throws401() {
        final AnonymousSession revoked = AnonymousSession.create("session-A");
        revoked.revoke(RevokedReason.USER_ROTATE);
        given(anonymousSessionRepository.findById("session-A")).willReturn(Optional.of(revoked));

        assertThatThrownBy(() -> sessionAuthGuard.verify("session-A", "session-A"))
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
        given(anonymousSessionRepository.findById("session-A")).willReturn(Optional.of(expired));

        assertThatThrownBy(() -> sessionAuthGuard.verify("session-A", "session-A"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(throwable -> ((ResponseStatusException) throwable).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(sessionActivityTracker, never()).markActive(any());
    }

    @Test
    @DisplayName("OK (행 존재 + 미revoke + 미만료) → 통과 + tracker.markActive 호출")
    void verify_active_passes_andCallsTracker() {
        final AnonymousSession active = AnonymousSession.create("session-A");
        given(anonymousSessionRepository.findById("session-A")).willReturn(Optional.of(active));

        sessionAuthGuard.verify("session-A", "session-A");

        verify(sessionActivityTracker, times(1)).markActive("session-A");
    }

    private static AnonymousSession expiredSession() {
        // sliding TTL 만료 시뮬레이션 — 200일 전 lastSeenAt 으로 reflection 없이 생성.
        // AnonymousSession 의 public ctor 가 PACKAGE 라 같은 패키지 helper 가 아니면 reflection 필요.
        // 여기서는 Mockito spy 로 lastSeenAt 만 stub.
        final AnonymousSession session = AnonymousSession.create("session-A");
        final AnonymousSession spy = Mockito.spy(session);
        given(spy.getLastSeenAt()).willReturn(LocalDateTime.now().minusDays(200));
        return spy;
    }
}
