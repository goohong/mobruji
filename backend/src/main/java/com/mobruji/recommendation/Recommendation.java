package com.mobruji.recommendation;

import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "recommendation", indexes = @Index(name = "ix_recommendation_request_rank", columnList = "recommendation_request_id, rank_position"))
@AllArgsConstructor(access = AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Recommendation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recommendation_request_id", nullable = false)
    private Long recommendationRequestId;

    @Column(name = "song_id", nullable = false)
    private Long songId;

    @Column(name = "score", nullable = false)
    private double score;

    @Column(name = "match_reason", nullable = false, length = 200)
    private String matchReason;

    @Column(name = "rank_position", nullable = false)
    private int rankPosition;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static Recommendation create(
            final Long recommendationRequestId,
            final Long songId,
            final double score,
            final String matchReason,
            final int rankPosition) {
        Objects.requireNonNull(recommendationRequestId, "recommendationRequestId must not be null");
        Objects.requireNonNull(songId, "songId must not be null");
        Objects.requireNonNull(matchReason, "matchReason must not be null");
        if (rankPosition < 1) {
            throw new IllegalArgumentException("rankPosition must be >= 1: " + rankPosition);
        }
        return new Recommendation(null, recommendationRequestId, songId, score, matchReason, rankPosition, LocalDateTime
                .now());
    }
}
