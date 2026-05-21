package com.mobruji.song.api.dto;

import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.Mood;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;

public record SongResponse(
        Long id,
        String title,
        String artist,
        Integer releaseYear,
        MusicalKey keyOriginal,
        Integer bpm,
        Mood mood,
        String language,
        String genre,
        String tjNumber,
        String kyNumber,
        MetadataSource metadataSource
) {

    public static SongResponse from(final Song song) {
        return new SongResponse(
                song.getId(),
                song.getTitle(),
                song.getArtist(),
                song.getReleaseYear(),
                song.getKeyOriginal(),
                song.getBpm(),
                song.getMood(),
                song.getLanguage(),
                song.getGenre(),
                song.getTjNumber(),
                song.getKyNumber(),
                song.getMetadataSource());
    }
}
