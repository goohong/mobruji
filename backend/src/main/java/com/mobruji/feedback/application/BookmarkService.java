package com.mobruji.feedback.application;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mobruji.feedback.domain.Bookmark;
import com.mobruji.feedback.infrastructure.BookmarkRepository;
import com.mobruji.song.domain.Song;
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

    /**
     * 세션의 북마크 페이지 + 해당 곡 메타데이터를 조합해 반환한다. {@link LikeService#readPageBySessionId(String, int, int)} 와 동일 패턴.
     */
    @Transactional(readOnly = true)
    public BookmarkPageSlice readPageBySessionId(final String sessionId, final int page, final int size) {
        final long totalCount = bookmarkRepository.countBySessionId(sessionId);
        if (totalCount == 0L) {
            return new BookmarkPageSlice(List.of(), Map.of(), totalCount);
        }
        final Pageable pageable = PageRequest.of(page, size);
        final List<Bookmark> bookmarks = bookmarkRepository.findBySessionIdOrderByCreatedAtDesc(sessionId, pageable);
        final List<Long> songIds = bookmarks.stream().map(Bookmark::getSongId).toList();
        final Map<Long, Song> songsById = songRepository.findAllById(songIds).stream()
                .collect(Collectors.toMap(Song::getId, Function.identity()));
        return new BookmarkPageSlice(bookmarks, songsById, totalCount);
    }

    public record BookmarkPageSlice(
            List<Bookmark> bookmarks,
            Map<Long, Song> songsById,
            long totalCount
    ) {
    }
}
