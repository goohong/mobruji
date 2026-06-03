package com.mobruji.recommendation.domain;

import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * 스와이프 세션 반응 1건(#1545 recommendation-feedback-loop.md §5-1). 익명 sessionId 단위로 곡당 최신 1건이 유지되며,
 * 같은 {@code (sessionId, songId)} 로 다시 스와이프하면 {@code reaction} 과 {@code createdAt} 을 덮어쓴다(upsert).
 *
 * <p>{@code feedback} BC 의 {@code Like}/{@code Bookmark}(toggle, 추천 비영향)와 달리, 본 엔티티는
 * {@code recommendation} context 가 소유하며 {@code next} 추천의 결합 신호로 환류된다 — {@code LIKE} 는 부른곡
 * 시드와 함께 선호 집합, {@code PASS} 는 회피 집합. Song aggregate 참조는 ID-only(ADR-0005 §A-7).
 */
@Getter
@Entity
@Table(
        name = "session_feedback", uniqueConstraints = @UniqueConstraint(name = "uk_session_feedback_session_song", columnNames = {
                "session_id",
                "song_id"}), indexes = @Index(name = "ix_session_feedback_session_created", columnList = "session_id, created_at"))
@AllArgsConstructor(access = AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SessionFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "song_id", nullable = false)
    private Long songId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reaction", nullable = false, length = 8)
    private FeedbackReaction reaction;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static SessionFeedback create(
            final String sessionId, final Long songId, final FeedbackReaction reaction) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(songId, "songId must not be null");
        Objects.requireNonNull(reaction, "reaction must not be null");
        return new SessionFeedback(null, sessionId, songId, reaction, LocalDateTime.now());
    }

    /**
     * 재스와이프 시 최신 반응으로 덮어쓴다(upsert). 최신성 감쇠가 새 상호작용 시점을 반영하도록 {@code createdAt} 도 갱신한다.
     */
    public void overwriteReaction(final FeedbackReaction newReaction) {
        Objects.requireNonNull(newReaction, "reaction must not be null");
        this.reaction = newReaction;
        this.createdAt = LocalDateTime.now();
    }
}
