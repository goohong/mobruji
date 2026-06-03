package com.mobruji.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.mobruji.recommendation.domain.FeedbackReaction;
import com.mobruji.recommendation.domain.SessionFeedback;
import com.mobruji.recommendation.infrastructure.SessionFeedbackRepository;
import com.mobruji.user.application.SessionRotationService;
import com.mobruji.user.domain.AnonymousSession;
import com.mobruji.user.infrastructure.AnonymousSessionRepository;

/**
 * sessionId cascade-delete 통합 테스트 — 실 DB(H2) 기준 repository count 검증.
 *
 * <p>spec: {@code docs/features/anonymous-session-lifecycle.md} §5-4, ADR-0013 §D-2 / §D-3.
 *
 * <p>{@code SessionRotationServiceTest} 는 deleter 를 mock 하므로 cascade 대상 누락(session_feedback
 * orphan)을 못 잡는다. 본 테스트는 실 행을 영속한 뒤 revoke 후 repository count 가 0 임을 검증해
 * cascade-delete 의 실제 효과를 보장한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class SessionDataCascadeDeleteIntegrationTest {

    @Autowired
    private SessionRotationService sessionRotationService;

    @Autowired
    private AnonymousSessionRepository anonymousSessionRepository;

    @Autowired
    private SessionFeedbackRepository sessionFeedbackRepository;

    @BeforeEach
    void setUp() {
        sessionFeedbackRepository.deleteAll();
        anonymousSessionRepository.deleteAll();
    }

    @Test
    @DisplayName("rotate: 세션 revoke 시 해당 sessionId 의 session_feedback 행이 모두 삭제된다")
    void rotate_cascadeDeletesSessionFeedback() {
        // given: 회전 대상 세션에 스와이프 반응 2건, 무관한 세션에 1건 영속
        final String rotatingSessionId = "rotating-session";
        final String otherSessionId = "other-session";
        anonymousSessionRepository.save(AnonymousSession.create(rotatingSessionId));
        anonymousSessionRepository.save(AnonymousSession.create(otherSessionId));
        sessionFeedbackRepository.save(SessionFeedback.create(rotatingSessionId, 101L, FeedbackReaction.LIKE));
        sessionFeedbackRepository.save(SessionFeedback.create(rotatingSessionId, 202L, FeedbackReaction.PASS));
        sessionFeedbackRepository.save(SessionFeedback.create(otherSessionId, 303L, FeedbackReaction.LIKE));

        // when: 세션 회전 (cascade-delete + revoke)
        sessionRotationService.rotate(rotatingSessionId);

        // then: 회전 세션의 반응은 0, 무관한 세션의 반응은 보존
        assertThat(sessionFeedbackRepository.countBySessionId(rotatingSessionId)).isZero();
        assertThat(sessionFeedbackRepository.countBySessionId(otherSessionId)).isEqualTo(1);
    }
}
