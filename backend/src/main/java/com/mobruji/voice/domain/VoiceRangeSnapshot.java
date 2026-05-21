package com.mobruji.voice.domain;

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

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 음역 측정 시계열 스냅샷. voice_range 가 "현재 값"을 담당하는 반면 본 엔티티는 변경 이력을 담당한다 (CQRS-라이트
 * 분리). insert-only / immutable 로 운영되며, voice_range upsert 와 동일 트랜잭션에서 추가된다.
 *
 * <p>spec: docs/features/voice-range-progress.md §5-1. 결정성 영향 없음 — 추천 입력에 사용되지 않는다.
 */
@Getter
@Entity
@Table(
        name = "voice_range_snapshot", indexes = @Index(name = "ix_voice_range_snapshot_session_measured", columnList = "session_id, measured_at"))
@AllArgsConstructor(access = AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VoiceRangeSnapshot {

    private static final int LOWEST_ALLOWED_MIDI = 12;
    private static final int HIGHEST_ALLOWED_MIDI = 119;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "low_midi", nullable = false)
    private int lowMidi;

    @Column(name = "high_midi", nullable = false)
    private int highMidi;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_method", nullable = false, length = 32)
    private VoiceRangeSourceMethod sourceMethod;

    @Column(name = "measured_at", nullable = false)
    private LocalDateTime measuredAt;

    public static VoiceRangeSnapshot create(
            final String sessionId,
            final int lowMidi,
            final int highMidi,
            final VoiceRangeSourceMethod sourceMethod) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(sourceMethod, "sourceMethod must not be null");
        validateRange(lowMidi, highMidi);
        return new VoiceRangeSnapshot(null, sessionId, lowMidi, highMidi, sourceMethod, LocalDateTime.now());
    }

    public static VoiceRangeSnapshot fromVoiceRange(final VoiceRange voiceRange) {
        Objects.requireNonNull(voiceRange, "voiceRange must not be null");
        return create(
                voiceRange.getSessionId(),
                voiceRange.getLowestNoteMidi(),
                voiceRange.getHighestNoteMidi(),
                voiceRange.getSourceMethod());
    }

    private static void validateRange(final int lowMidi, final int highMidi) {
        if (lowMidi < LOWEST_ALLOWED_MIDI || lowMidi > HIGHEST_ALLOWED_MIDI) {
            throw new IllegalArgumentException(
                    "lowMidi out of allowed range [" + LOWEST_ALLOWED_MIDI + ", " + HIGHEST_ALLOWED_MIDI + "]: "
                            + lowMidi);
        }
        if (highMidi < LOWEST_ALLOWED_MIDI || highMidi > HIGHEST_ALLOWED_MIDI) {
            throw new IllegalArgumentException(
                    "highMidi out of allowed range [" + LOWEST_ALLOWED_MIDI + ", " + HIGHEST_ALLOWED_MIDI + "]: "
                            + highMidi);
        }
        if (lowMidi > highMidi) {
            throw new IllegalArgumentException(
                    "lowMidi (" + lowMidi + ") must be <= highMidi (" + highMidi + ")");
        }
    }
}
