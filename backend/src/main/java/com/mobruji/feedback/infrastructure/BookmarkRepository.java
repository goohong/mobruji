package com.mobruji.feedback.infrastructure;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mobruji.feedback.domain.Bookmark;

public interface BookmarkRepository extends JpaRepository<Bookmark, Long> {

    List<Bookmark> findBySessionIdOrderByCreatedAtDesc(String sessionId);

    boolean existsBySessionIdAndSongId(String sessionId, Long songId);

    long deleteBySessionIdAndSongId(String sessionId, Long songId);
}
