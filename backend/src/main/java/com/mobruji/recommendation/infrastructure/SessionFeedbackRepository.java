package com.mobruji.recommendation.infrastructure;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.mobruji.recommendation.domain.FeedbackReaction;
import com.mobruji.recommendation.domain.SessionFeedback;

public interface SessionFeedbackRepository extends JpaRepository<SessionFeedback, Long> {

    Optional<SessionFeedback> findBySessionIdAndSongId(String sessionId, Long songId);

    /**
     * 세션의 반응을 삽입 순서(최신순) 로 페이지 단위 조회한다. {@link Pageable} 의 sort 는 메서드명 정렬을 따른다 —
     * fe 응답 안정성 위해 정렬 키 고정({@code createdAt DESC}).
     */
    List<SessionFeedback> findBySessionIdOrderByCreatedAtDesc(String sessionId, Pageable pageable);

    long countBySessionId(String sessionId);

    /**
     * {@code next} 결합 신호 도출용 — 세션의 특정 reaction(LIKE/PASS) 곡 ID 를 오래된순으로 조회한다.
     * 선호/회피 집합 구성 시 N+1 없이 1쿼리로 가져온다(spec §3 비기능 성능).
     */
    List<SessionFeedback> findBySessionIdAndReactionOrderByCreatedAtAsc(
            String sessionId, FeedbackReaction reaction);
}
