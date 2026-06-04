package com.mobruji.recommendation.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mobruji.recommendation.domain.FeedbackReaction;
import com.mobruji.recommendation.domain.SessionFeedback;
import com.mobruji.recommendation.infrastructure.SessionFeedbackRepository;
import com.mobruji.song.domain.SongNotFoundException;
import com.mobruji.song.infrastructure.SongRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 스와이프 세션 반응 기록·조회 application service(#1545 recommendation-feedback-loop.md §5-2).
 *
 * <p>기록은 toggle 이 아니라 upsert — 같은 {@code (sessionId, songId)} 로 다시 호출하면 최신 reaction 으로
 * 덮어쓴다(like→pass 전환도 한 번의 POST). {@code feedback} BC 의 {@code LikeService}(toggle) 와 의도적으로
 * 다르다(스와이프는 "현재 상태 설정" 의미, spec §5-2 / §9).
 *
 * <p>로깅 정책: sessionId 원문 노출 금지({@code docs/ai-harness/04-security-policy.md} 익명 세션 룰).
 * SHA-256 hex prefix({@value #SESSION_HASH_HEX_LENGTH}자)만 노출.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class SessionFeedbackService {

    /** sessionId 로그 노출 시 사용하는 SHA-256 hex prefix 길이. */
    private static final int SESSION_HASH_HEX_LENGTH = 12;

    private static final String HASH_ALGORITHM = "SHA-256";

    private final SessionFeedbackRepository sessionFeedbackRepository;
    private final SongRepository songRepository;

    /**
     * 스와이프 반응 upsert. 같은 곡의 기존 반응이 있으면 최신 reaction 으로 덮어쓰고, 없으면 새로 저장한다.
     * 존재하지 않는 songId 면 404({@link SongNotFoundException}).
     */
    public SessionFeedback record(
            final String sessionId, final Long songId, final FeedbackReaction reaction) {
        if (!songRepository.existsById(songId)) {
            log.warn(
                    "event=recommendation.feedback.record.song_not_found session_hash={} song_id={}",
                    hashSessionId(sessionId), songId);
            throw new SongNotFoundException(songId);
        }
        final Optional<SessionFeedback> existing = sessionFeedbackRepository.findBySessionIdAndSongId(sessionId,
                songId);
        final SessionFeedback saved;
        if (existing.isPresent()) {
            final SessionFeedback feedback = existing.get();
            feedback.overwriteReaction(reaction);
            saved = sessionFeedbackRepository.save(feedback);
        } else {
            saved = sessionFeedbackRepository.save(SessionFeedback.create(sessionId, songId, reaction));
        }
        log.info(
                "event=recommendation.feedback.recorded session_hash={} song_id={} reaction={}",
                hashSessionId(sessionId), songId, reaction);
        return saved;
    }

    /**
     * 세션 반응을 최신순(createdAt DESC) 페이지로 조회한다. 곡 메타 join 없이 반응 그 자체(songId/reaction/createdAt)만
     * 반환한다 — 호출 측 fe 는 이미 보유한 카드 메타로 렌더한다.
     */
    @Transactional(readOnly = true)
    public SessionFeedbackPageSlice readPageBySessionId(
            final String sessionId, final int page, final int size) {
        final long totalCount = sessionFeedbackRepository.countBySessionId(sessionId);
        if (totalCount == 0L) {
            return new SessionFeedbackPageSlice(List.of(), totalCount);
        }
        final Pageable pageable = PageRequest.of(page, size);
        final List<SessionFeedback> feedbacks = sessionFeedbackRepository.findBySessionIdOrderByCreatedAtDesc(sessionId,
                pageable);
        return new SessionFeedbackPageSlice(feedbacks, totalCount);
    }

    /**
     * 페이지네이션 결과 슬라이스 — 컨트롤러가 DTO 로 변환할 때 사용한다.
     */
    public record SessionFeedbackPageSlice(
            List<SessionFeedback> feedbacks,
            long totalCount
    ) {
    }

    /**
     * sessionId 원문 노출 금지({@code 04-security-policy} 익명 세션 룰). SHA-256 hex prefix
     * {@value #SESSION_HASH_HEX_LENGTH}자만 반환 — 원문 복원 불가, 디버깅 식별자 용도.
     */
    private static String hashSessionId(final String sessionId) {
        if (sessionId == null) {
            return "unknown";
        }
        try {
            final MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            final byte[] hash = digest.digest(sessionId.getBytes(StandardCharsets.UTF_8));
            final String hex = HexFormat.of().formatHex(hash);
            return hex.substring(0, Math.min(SESSION_HASH_HEX_LENGTH, hex.length()));
        } catch (final NoSuchAlgorithmException e) {
            return "unhashable";
        }
    }
}
