package com.mobruji.song.api.dto;

import com.mobruji.song.domain.Difficulty;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.Mood;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.NoteName;
import com.mobruji.song.domain.Song;
import com.mobruji.song.domain.SongAnalysisProfile;

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
        MetadataSource metadataSource,
        String isrc,
        double metadataConfidence,
        Integer lowMidi,
        Integer highMidi,
        Difficulty difficulty,
        Float energy,
        String lowestNoteName,
        String highestNoteName,
        String albumCoverUrl,
        SongAnalysisProfile analysisProfile
) {

    public static SongResponse from(final Song song) {
        final Integer songLowMidi = song.getLowMidi();
        final Integer songHighMidi = song.getHighMidi();
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
                song.getMetadataSource(),
                song.getIsrc(),
                song.getMetadataConfidence(),
                songLowMidi,
                songHighMidi,
                song.getDifficulty(),
                song.getEnergy(),
                songLowMidi != null ? NoteName.of(songLowMidi) : null,
                songHighMidi != null ? NoteName.of(songHighMidi) : null,
                song.getAlbumCoverUrl(),
                SongAnalysisProfile.from(song));
    }
}
