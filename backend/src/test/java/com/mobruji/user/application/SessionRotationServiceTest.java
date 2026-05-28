package com.mobruji.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.mobruji.user.domain.AnonymousSession;
import com.mobruji.user.infrastructure.AnonymousSessionRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * {@link SessionRotationService} 단위 테스트 — 회전 흐름의 부수 효과 검증.
 *
 * <p>spec: {@code docs/features/anonymous-session-lifecycle.md} §5-4 + §5-2 메모리 누수 방어
 * (PR #937 follow-up). repository/cascade/tracker 의 협력 호출 매트릭스 가드.
 */
class SessionRotationServiceTest {

    private static final String OLD_SESSION_ID = "550e8400-e29b-41d4-a716-446655440000";

    private AnonymousSessionRepository anonymousSessionRepository;
    private SessionDataCascadeDeleter sessionDataCascadeDeleter;
    private SessionActivityTracker sessionActivityTracker;
    private SessionRotationService sessionRotationService;

    @BeforeEach
    void setUp() {
        anonymousSessionRepository = Mockito.mock(AnonymousSessionRepository.class);
        sessionDataCascadeDeleter = Mockito.mock(SessionDataCascadeDeleter.class);
        sessionActivityTracker = Mockito.mock(SessionActivityTracker.class);
        sessionRotationService = new SessionRotationService(
                anonymousSessionRepository,
                sessionDataCascadeDeleter,
                sessionActivityTracker,
                new SimpleMeterRegistry());

        // default — bootstrap-on-rotate (행 없음 시나리오).
        given(anonymousSessionRepository.findById(any())).willReturn(Optional.empty());
        given(anonymousSessionRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("rotate 성공 — 옛 sessionId 의 in-memory cache evict (메모리 누수 방어, PR #937 follow-up)")
    void rotate_evictsOldSessionFromActivityCache() {
        sessionRotationService.rotate(OLD_SESSION_ID);

        verify(sessionActivityTracker, times(1)).evict(OLD_SESSION_ID);
    }

    @Test
    @DisplayName("rotate 성공 — 새 sessionId UUIDv4 발급 + 옛 sessionId revoke 영속")
    void rotate_returnsNewUuidAndRevokesOld() {
        final String newSessionId = sessionRotationService.rotate(OLD_SESSION_ID);

        assertThat(newSessionId).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
        verify(sessionDataCascadeDeleter, times(1)).cascadeDelete(OLD_SESSION_ID);
        // 옛 sessionId revoke + 새 sessionId 생성 — save 2회.
        verify(anonymousSessionRepository, times(2)).save(any(AnonymousSession.class));
    }

    @Test
    @DisplayName("rotate currentSessionId null → NPE")
    void rotate_nullInput_throws() {
        assertThatThrownBy(() -> sessionRotationService.rotate(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("rotate currentSessionId blank → IllegalArgumentException")
    void rotate_blankInput_throws() {
        assertThatThrownBy(() -> sessionRotationService.rotate("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
