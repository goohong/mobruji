package com.mobruji.song.domain;

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
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "song", indexes = {
        @Index(name = "ix_song_title", columnList = "title"),
        @Index(name = "ix_song_artist", columnList = "artist"),
})
@AllArgsConstructor(access = AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Song {

    /**
     * 가창 난이도 자동 분류 임계값. fe(`web/lib/difficulty.ts`)와 1:1 일치.
     *
     * <ul>
     * <li>HARD: highMidi ≥ 76 (E5) 또는 (highMidi - lowMidi) ≥ 17 반음</li>
     * <li>NORMAL: 71 ≤ highMidi ≤ 75 (B4 ~ D#5)</li>
     * <li>EASY: highMidi < 71</li>
     * </ul>
     *
     * 기준 영속화는 ADR 0007 후보(본진 후속).
     */
    public static final int HIGH_HARD_THRESHOLD = 76;
    public static final int HIGH_NORMAL_THRESHOLD = 71;
    public static final int SPAN_HARD_THRESHOLD = 17;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 200)
    private String artist;

    @Column(name = "release_year")
    private Integer releaseYear;

    @Enumerated(EnumType.STRING)
    @Column(name = "key_original", nullable = false, length = 24)
    private MusicalKey keyOriginal;

    @Column
    private Integer bpm;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Mood mood;

    @Column(length = 32)
    private String language;

    @Column(length = 32)
    private String genre;

    @Column(name = "tj_number", length = 16)
    private String tjNumber;

    @Column(name = "ky_number", length = 16)
    private String kyNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "metadata_source", nullable = false, length = 24)
    private MetadataSource metadataSource;

    /**
     * 곡 보컬 멜로디의 최저음 (MIDI note number).
     * nullable — 시드/외부 출처에 따라 미보유 가능. {@link #difficulty} 자동 계산은
     * lowMidi/highMidi가 둘 다 있을 때만 수행한다.
     */
    @Column(name = "low_midi")
    private Integer lowMidi;

    /**
     * 곡 보컬 멜로디의 최고음 (MIDI note number).
     */
    @Column(name = "high_midi")
    private Integer highMidi;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Difficulty difficulty;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    public static Song create(
            final String title,
            final String artist,
            final Integer releaseYear,
            final MusicalKey keyOriginal,
            final Integer bpm,
            final Mood mood,
            final String language,
            final String genre,
            final String tjNumber,
            final String kyNumber,
            final MetadataSource metadataSource,
            final Integer lowMidi,
            final Integer highMidi,
            final Difficulty difficulty) {
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(artist, "artist must not be null");
        Objects.requireNonNull(keyOriginal, "keyOriginal must not be null");
        Objects.requireNonNull(metadataSource, "metadataSource must not be null");
        if (title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (artist.isBlank()) {
            throw new IllegalArgumentException("artist must not be blank");
        }
        if (bpm != null && (bpm < 30 || bpm > 300)) {
            throw new IllegalArgumentException("bpm out of plausible range [30, 300]: " + bpm);
        }
        if (lowMidi != null && highMidi != null && lowMidi > highMidi) {
            throw new IllegalArgumentException(
                    "lowMidi must not exceed highMidi: lowMidi=" + lowMidi + ", highMidi=" + highMidi);
        }
        // difficulty가 명시되지 않으면 lowMidi/highMidi로 자동 분류 (둘 다 있을 때만).
        final Difficulty resolvedDifficulty = difficulty != null
                ? difficulty
                : (lowMidi != null && highMidi != null ? deriveDifficulty(lowMidi, highMidi) : null);
        final LocalDateTime now = LocalDateTime.now();
        return new Song(
                null, title, artist, releaseYear, keyOriginal, bpm, mood, language, genre,
                tjNumber, kyNumber, metadataSource, lowMidi, highMidi, resolvedDifficulty, now, now);
    }

    /**
     * 곡 음역(MIDI)으로부터 가창 난이도를 분류한다. fe `web/lib/difficulty.ts`와 동일한 규칙.
     *
     * <ul>
     * <li>HARD: highMidi ≥ 76 또는 (highMidi - lowMidi) ≥ 17</li>
     * <li>NORMAL: 71 ≤ highMidi ≤ 75</li>
     * <li>EASY: highMidi < 71</li>
     * </ul>
     *
     * 잘못된 입력(예: lowMidi > highMidi)에 대한 검증은 호출 측에서. 본 메서드는 단순 분류만.
     */
    public static Difficulty deriveDifficulty(final int lowMidi, final int highMidi) {
        final int span = highMidi - lowMidi;
        if (highMidi >= HIGH_HARD_THRESHOLD || span >= SPAN_HARD_THRESHOLD) {
            return Difficulty.HARD;
        }
        if (highMidi >= HIGH_NORMAL_THRESHOLD) {
            return Difficulty.NORMAL;
        }
        return Difficulty.EASY;
    }
}
