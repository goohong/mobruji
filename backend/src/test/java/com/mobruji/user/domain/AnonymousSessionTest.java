package com.mobruji.user.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AnonymousSessionTest {

    @Test
    @DisplayName("create: 유효한 sessionId 로 firstSeenAt == lastSeenAt 인 활성 행을 만든다")
    void create_withValidSessionId_returnsActiveSession() {
        // given
        final String sessionId = "550e8400-e29b-41d4-a716-446655440000";

        // when
        final AnonymousSession anonymousSession = AnonymousSession.create(sessionId);

        // then
        assertThat(anonymousSession.getSessionId()).isEqualTo(sessionId);
        assertThat(anonymousSession.getFirstSeenAt()).isNotNull();
        assertThat(anonymousSession.getLastSeenAt()).isEqualTo(anonymousSession.getFirstSeenAt());
        assertThat(anonymousSession.getRevokedAt()).isNull();
        assertThat(anonymousSession.getRevokedReason()).isNull();
        assertThat(anonymousSession.isRevoked()).isFalse();
    }

    @Test
    @DisplayName("create: sessionId null → NullPointerException")
    void create_withNullSessionId_throws() {
        assertThatThrownBy(() -> AnonymousSession.create(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sessionId");
    }

    @Test
    @DisplayName("create: sessionId blank → IllegalArgumentException")
    void create_withBlankSessionId_throws() {
        assertThatThrownBy(() -> AnonymousSession.create("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    @DisplayName("create: sessionId 가 SESSION_ID_MAX_LENGTH 초과 → IllegalArgumentException")
    void create_withTooLongSessionId_throws() {
        final String tooLong = "x".repeat(AnonymousSession.SESSION_ID_MAX_LENGTH + 1);
        assertThatThrownBy(() -> AnonymousSession.create(tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("length exceeds");
    }

    @Test
    @DisplayName("touch: 활성 sessionId 의 lastSeenAt 을 갱신한다")
    void touch_active_updatesLastSeenAt() throws InterruptedException {
        // given
        final AnonymousSession anonymousSession = AnonymousSession.create("s");
        final java.time.LocalDateTime before = anonymousSession.getLastSeenAt();
        Thread.sleep(2);

        // when
        anonymousSession.touch();

        // then
        assertThat(anonymousSession.getLastSeenAt()).isAfter(before);
        assertThat(anonymousSession.getFirstSeenAt()).isEqualTo(before);
    }

    @Test
    @DisplayName("touch: revoked sessionId → IllegalStateException")
    void touch_revoked_throws() {
        final AnonymousSession anonymousSession = AnonymousSession.create("s");
        anonymousSession.revoke(RevokedReason.TTL);

        assertThatThrownBy(anonymousSession::touch)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("revoked");
    }

    @Test
    @DisplayName("revoke: revokedAt + revokedReason 마킹 + isRevoked() true")
    void revoke_marksRevoked() {
        final AnonymousSession anonymousSession = AnonymousSession.create("s");

        anonymousSession.revoke(RevokedReason.USER_ROTATE);

        assertThat(anonymousSession.getRevokedAt()).isNotNull();
        assertThat(anonymousSession.getRevokedReason()).isEqualTo(RevokedReason.USER_ROTATE);
        assertThat(anonymousSession.isRevoked()).isTrue();
    }

    @Test
    @DisplayName("revoke: 이미 revoked 면 idempotent — 기존 값 보존")
    void revoke_alreadyRevoked_isIdempotent() {
        final AnonymousSession anonymousSession = AnonymousSession.create("s");
        anonymousSession.revoke(RevokedReason.TTL);
        final java.time.LocalDateTime originalRevokedAt = anonymousSession.getRevokedAt();

        anonymousSession.revoke(RevokedReason.USER_ROTATE);

        assertThat(anonymousSession.getRevokedReason()).isEqualTo(RevokedReason.TTL);
        assertThat(anonymousSession.getRevokedAt()).isEqualTo(originalRevokedAt);
    }

    @Test
    @DisplayName("revoke: revokedReason null → NullPointerException")
    void revoke_withNullReason_throws() {
        final AnonymousSession anonymousSession = AnonymousSession.create("s");

        assertThatThrownBy(() -> anonymousSession.revoke(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("revokedReason");
    }
}
