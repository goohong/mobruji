package com.mobruji.song.application;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.mobruji.song.domain.Song;
import com.mobruji.song.domain.SongNotFoundException;
import com.mobruji.song.infrastructure.SongRepository;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SongService {

    private final SongRepository songRepository;

    public Song readById(final Long id) {
        return songRepository.findById(id)
                .orElseThrow(() -> new SongNotFoundException(id));
    }

    public List<Song> searchByKeyword(final String keyword) {
        final String normalized = keyword == null ? "" : keyword.trim();
        if (normalized.isEmpty()) {
            return List.of();
        }
        return songRepository.searchByKeyword(normalized);
    }
}
