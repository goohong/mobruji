package com.mobruji.song.dto;

import com.mobruji.song.MetadataSource;
import com.mobruji.song.Mood;
import com.mobruji.song.MusicalKey;
import com.mobruji.song.Song;

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
