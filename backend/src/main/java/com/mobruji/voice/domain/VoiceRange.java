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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "voice_range", uniqueConstraints = @UniqueConstraint(name = "uk_voice_range_session_id", columnNames = "session_id"))
@AllArgsConstructor(access = AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VoiceRange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, unique = true, length = 64)
    private String sessionId;

    @Column(name = "lowest_note_midi", nullable = false)
    private int lowestNoteMidi;

    @Column(name = "highest_note_midi", nullable = false)
    private int highestNoteMidi;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_method", nullable = false, length = 32)
    private VoiceRangeSourceMethod sourceMethod;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static VoiceRange create(
            final String sessionId,
            final int lowestNoteMidi,
            final int highestNoteMidi,
            final VoiceRangeSourceMethod sourceMethod) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(sourceMethod, "sourceMethod must not be null");
        validateRange(lowestNoteMidi, highestNoteMidi);
        final LocalDateTime now = LocalDateTime.now();
        return new VoiceRange(null, sessionId, lowestNoteMidi, highestNoteMidi, sourceMethod, now, now);
    }

    public void updateRange(
            final int lowestNoteMidi,
            final int highestNoteMidi,
            final VoiceRangeSourceMethod sourceMethod) {
        Objects.requireNonNull(sourceMethod, "sourceMethod must not be null");
        validateRange(lowestNoteMidi, highestNoteMidi);
        this.lowestNoteMidi = lowestNoteMidi;
        this.highestNoteMidi = highestNoteMidi;
        this.sourceMethod = sourceMethod;
        this.updatedAt = LocalDateTime.now();
    }

    private static void validateRange(final int lowestNoteMidi, final int highestNoteMidi) {
        if (lowestNoteMidi < MidiRange.LOWEST_ALLOWED_MIDI || lowestNoteMidi > MidiRange.HIGHEST_ALLOWED_MIDI) {
            throw new IllegalArgumentException(
                    "lowestNoteMidi out of allowed range [" + MidiRange.LOWEST_ALLOWED_MIDI + ", "
                            + MidiRange.HIGHEST_ALLOWED_MIDI + "]: " + lowestNoteMidi);
        }
        if (highestNoteMidi < MidiRange.LOWEST_ALLOWED_MIDI || highestNoteMidi > MidiRange.HIGHEST_ALLOWED_MIDI) {
            throw new IllegalArgumentException(
                    "highestNoteMidi out of allowed range [" + MidiRange.LOWEST_ALLOWED_MIDI + ", "
                            + MidiRange.HIGHEST_ALLOWED_MIDI + "]: " + highestNoteMidi);
        }
        if (lowestNoteMidi > highestNoteMidi) {
            throw new IllegalArgumentException(
                    "lowestNoteMidi (" + lowestNoteMidi + ") must be <= highestNoteMidi (" + highestNoteMidi + ")");
        }
    }
}
