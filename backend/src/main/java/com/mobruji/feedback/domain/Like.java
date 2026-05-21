package com.mobruji.feedback.domain;

import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자가 곡에 남긴 긍정 시그널. 익명 sessionId 단위로 곡당 1건이 유지된다. v0.2 단계에서는 추천 알고리즘 가중치에 영향을 주지 않는다(시그널
 * 수집 전용; spec recommendation-history-and-feedback.md §3 비기능 / §9 결정 로그).
 *
 * <p>Song aggregate 참조는 ID-only로 한다(ADR 0005 §A-7 cross-BC 권장 패턴).
 */
@Getter
@Entity
@Table(
        name = "like_feedback", uniqueConstraints = @UniqueConstraint(name = "uk_like_feedback_session_song", columnNames = {
                "session_id",
                "song_id"}), indexes = @Index(name = "ix_like_feedback_session_created", columnList = "session_id, created_at"))
@AllArgsConstructor(access = AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Like {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "song_id", nullable = false)
    private Long songId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static Like create(final String sessionId, final Long songId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(songId, "songId must not be null");
        return new Like(null, sessionId, songId, LocalDateTime.now());
    }
}
