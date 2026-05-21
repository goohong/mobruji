package com.mobruji.feedback.infrastructure;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.mobruji.feedback.domain.Like;

public interface LikeRepository extends JpaRepository<Like, Long> {

    List<Like> findBySessionIdOrderByCreatedAtDesc(String sessionId);

    /**
     * 세션의 좋아요를 최신순으로 페이지 단위 조회한다. {@link Pageable} 의 sort 는 메서드명 정렬을 따른다(인자
     * 로 받은 sort 는 무시) — fe 응답 안정성 위해 정렬 키 고정.
     */
    List<Like> findBySessionIdOrderByCreatedAtDesc(String sessionId, Pageable pageable);

    long countBySessionId(String sessionId);

    boolean existsBySessionIdAndSongId(String sessionId, Long songId);

    long deleteBySessionIdAndSongId(String sessionId, Long songId);
}
