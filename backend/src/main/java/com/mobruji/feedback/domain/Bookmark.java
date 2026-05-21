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
 * 사용자가 곡을 다시 찾고 싶어 별도 큐에 담은 행위. sessionId 단위로 곡당 1건이 유지된다(toggle). Song aggregate 참조는 ID-only(ADR
 * 0005 §A-7).
 */
@Getter
@Entity
@Table(
        name = "bookmark_feedback", uniqueConstraints = @UniqueConstraint(name = "uk_bookmark_feedback_session_song", columnNames = {
                "session_id",
                "song_id"}), indexes = @Index(name = "ix_bookmark_feedback_session_created", columnList = "session_id, created_at"))
@AllArgsConstructor(access = AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Bookmark {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "song_id", nullable = false)
    private Long songId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static Bookmark create(final String sessionId, final Long songId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(songId, "songId must not be null");
        return new Bookmark(null, sessionId, songId, LocalDateTime.now());
    }
}
