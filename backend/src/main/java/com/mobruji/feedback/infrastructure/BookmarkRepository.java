package com.mobruji.feedback.infrastructure;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.mobruji.feedback.domain.Bookmark;

public interface BookmarkRepository extends JpaRepository<Bookmark, Long> {

    List<Bookmark> findBySessionIdOrderByCreatedAtDesc(String sessionId);

    /**
     * 세션의 북마크를 최신순으로 페이지 단위 조회한다. {@link LikeRepository#findBySessionIdOrderByCreatedAtDesc(String, Pageable)} 와 동일 패턴.
     */
    List<Bookmark> findBySessionIdOrderByCreatedAtDesc(String sessionId, Pageable pageable);

    long countBySessionId(String sessionId);

    boolean existsBySessionIdAndSongId(String sessionId, Long songId);

    long deleteBySessionIdAndSongId(String sessionId, Long songId);
}
