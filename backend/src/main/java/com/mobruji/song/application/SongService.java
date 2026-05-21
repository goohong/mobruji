package com.mobruji.song.application;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mobruji.song.api.dto.SongResponse;

import lombok.RequiredArgsConstructor;

import com.mobruji.song.domain.SongNotFoundException;
import com.mobruji.song.infrastructure.SongRepository;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SongService {

    private final SongRepository songRepository;

    public SongResponse readById(final Long id) {
        return songRepository.findById(id)
                .map(SongResponse::from)
                .orElseThrow(() -> new SongNotFoundException(id));
    }

    public List<SongResponse> searchByKeyword(final String keyword) {
        final String normalized = keyword == null ? "" : keyword.trim();
        if (normalized.isEmpty()) {
            return List.of();
        }
        return songRepository.searchByKeyword(normalized).stream()
                .map(SongResponse::from)
                .toList();
    }
}
