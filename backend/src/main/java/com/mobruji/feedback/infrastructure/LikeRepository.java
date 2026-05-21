package com.mobruji.feedback.infrastructure;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mobruji.feedback.domain.Like;

public interface LikeRepository extends JpaRepository<Like, Long> {

    List<Like> findBySessionIdOrderByCreatedAtDesc(String sessionId);

    boolean existsBySessionIdAndSongId(String sessionId, Long songId);

    long deleteBySessionIdAndSongId(String sessionId, Long songId);
}
