package com.mobruji.recommendation;

import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.mobruji.song.Mood;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 추천 요청 1건 (영속화 단위). 이름이 `RecommendationRequestDto`와 헷갈리지 않도록 `Entity` 접미사.
 */
@Getter
@Entity
@Table(name = "recommendation_request")
@AllArgsConstructor(access = AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecommendationRequestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "voice_range_low", nullable = false)
    private int voiceRangeLow;

    @Column(name = "voice_range_high", nullable = false)
    private int voiceRangeHigh;

    @Enumerated(EnumType.STRING)
    @Column(name = "mood", length = 16)
    private Mood mood;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static RecommendationRequestEntity create(
            final String sessionId,
            final int voiceRangeLow,
            final int voiceRangeHigh,
            final Mood mood) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (voiceRangeLow > voiceRangeHigh) {
            throw new IllegalArgumentException(
                    "voiceRangeLow (" + voiceRangeLow + ") must be <= voiceRangeHigh (" + voiceRangeHigh + ")");
        }
        return new RecommendationRequestEntity(null, sessionId, voiceRangeLow, voiceRangeHigh, mood, LocalDateTime
                .now());
    }
}
