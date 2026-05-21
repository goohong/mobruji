package com.mobruji.song;

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
            final MetadataSource metadataSource) {
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
        final LocalDateTime now = LocalDateTime.now();
        return new Song(
                null, title, artist, releaseYear, keyOriginal, bpm, mood, language, genre,
                tjNumber, kyNumber, metadataSource, now, now);
    }
}
