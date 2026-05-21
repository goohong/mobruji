package com.mobruji.feedback.application;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mobruji.feedback.domain.Bookmark;
import com.mobruji.feedback.infrastructure.BookmarkRepository;
import com.mobruji.song.domain.SongNotFoundException;
import com.mobruji.song.infrastructure.SongRepository;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class BookmarkService {

    private final BookmarkRepository bookmarkRepository;
    private final SongRepository songRepository;

    public ToggleResult toggle(final String sessionId, final Long songId) {
        if (!songRepository.existsById(songId)) {
            throw new SongNotFoundException(songId);
        }
        if (bookmarkRepository.existsBySessionIdAndSongId(sessionId, songId)) {
            bookmarkRepository.deleteBySessionIdAndSongId(sessionId, songId);
            return new ToggleResult(false, songId);
        }
        bookmarkRepository.save(Bookmark.create(sessionId, songId));
        return new ToggleResult(true, songId);
    }

    @Transactional(readOnly = true)
    public List<Bookmark> readBySessionId(final String sessionId) {
        return bookmarkRepository.findBySessionIdOrderByCreatedAtDesc(sessionId);
    }
}
