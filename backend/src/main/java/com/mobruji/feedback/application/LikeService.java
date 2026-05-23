package com.mobruji.feedback.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mobruji.feedback.domain.Like;
import com.mobruji.feedback.infrastructure.LikeRepository;
import com.mobruji.song.domain.Song;
import com.mobruji.song.domain.SongNotFoundException;
import com.mobruji.song.infrastructure.SongRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Like toggle application service.
 *
 * <p>spec: {@code docs/features/recommendation-history-and-feedback.md} §3 비기능 — toggle 분기 직후
 * {@link #METRIC_LIKE_CREATED} / {@link #METRIC_LIKE_DELETED} 카운터 +1. 메트릭 이름은
 * {@code docs/features/observability-baseline.md} §5-3 표가 단일 진실
 * (prefix {@code mobruji.recommendationhistory.*}).
 *
 * <p>로깅 정책: sessionId 원문 노출 금지 ({@code docs/ai-harness/04-security-policy.md} 익명 세션 룰).
 * SHA-256 hex prefix({@value #SESSION_HASH_HEX_LENGTH}자)만 노출 — voice {@code VoiceRangeService}
 * 와 동일 패턴.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class LikeService {

    /**
     * Micrometer counter — 좋아요 생성 1건당 +1.
     * spec {@code docs/features/observability-baseline.md} §5-3 표 단일 진실.
     */
    static final String METRIC_LIKE_CREATED = "mobruji.recommendationhistory.like.created";

    /**
     * Micrometer counter — 좋아요 취소 1건당 +1.
     * spec {@code docs/features/observability-baseline.md} §5-3 표 단일 진실.
     */
    static final String METRIC_LIKE_DELETED = "mobruji.recommendationhistory.like.deleted";

    /** sessionId 로그 노출 시 사용하는 SHA-256 hex prefix 길이. */
    private static final int SESSION_HASH_HEX_LENGTH = 12;

    private static final String HASH_ALGORITHM = "SHA-256";

    private final LikeRepository likeRepository;
    private final SongRepository songRepository;
    private final MeterRegistry meterRegistry;

    /**
     * 좋아요 토글. 이미 존재하면 삭제 → liked=false, 없으면 생성 → liked=true.
     *
     * <p>존재하지 않는 songId면 404(SongNotFoundException). 같은 (session, song) 동시 요청은
     * unique constraint로 막힌다.
     */
    public ToggleResult toggle(final String sessionId, final Long songId) {
        if (!songRepository.existsById(songId)) {
            log.warn(
                    "event=feedback.like.toggle.song_not_found session_hash={} song_id={}",
                    hashSessionId(sessionId), songId);
            throw new SongNotFoundException(songId);
        }
        if (likeRepository.existsBySessionIdAndSongId(sessionId, songId)) {
            likeRepository.deleteBySessionIdAndSongId(sessionId, songId);
            incrementCounter(METRIC_LIKE_DELETED, "좋아요 취소 1건 누적 (spec: recommendation-history-and-feedback §3 비기능)");
            log.info(
                    "event=feedback.like.deleted session_hash={} song_id={}",
                    hashSessionId(sessionId), songId);
            return new ToggleResult(false, songId);
        }
        likeRepository.save(Like.create(sessionId, songId));
        incrementCounter(METRIC_LIKE_CREATED, "좋아요 등록 1건 누적 (spec: recommendation-history-and-feedback §3 비기능)");
        log.info(
                "event=feedback.like.created session_hash={} song_id={}",
                hashSessionId(sessionId), songId);
        return new ToggleResult(true, songId);
    }

    @Transactional(readOnly = true)
    public List<Like> readBySessionId(final String sessionId) {
        return likeRepository.findBySessionIdOrderByCreatedAtDesc(sessionId);
    }

    /**
     * 세션의 좋아요 페이지 + 해당 곡 메타데이터를 조합해 반환한다. fe `/likes` 페이지가 곡 카드를 즉시 렌더할 수 있도록
     * N+1 회피를 위한 batch lookup({@code findAllById})을 사용한다.
     *
     * <p>{@link Like} 엔티티는 ID-only 참조(ADR-0005 §A-7)이므로 JPA association 대신 application 레이어에서
     * 합친다. 곡 삭제 등으로 lookup 에 없는 항목은 응답에서 제외한다 (호출 측은 totalCount 와의 차이를 무시 가능).
     *
     * @param sessionId 세션 ID
     * @param page      0-base 페이지 번호 (호출 측에서 검증된 값)
     * @param size      페이지 크기 (호출 측에서 검증된 값)
     */
    @Transactional(readOnly = true)
    public LikePageSlice readPageBySessionId(final String sessionId, final int page, final int size) {
        final long totalCount = likeRepository.countBySessionId(sessionId);
        if (totalCount == 0L) {
            return new LikePageSlice(List.of(), Map.of(), totalCount);
        }
        final Pageable pageable = PageRequest.of(page, size);
        final List<Like> likes = likeRepository.findBySessionIdOrderByCreatedAtDesc(sessionId, pageable);
        final List<Long> songIds = likes.stream().map(Like::getSongId).toList();
        final Map<Long, Song> songsById = songRepository.findAllById(songIds).stream()
                .collect(Collectors.toMap(Song::getId, Function.identity()));
        return new LikePageSlice(likes, songsById, totalCount);
    }

    /**
     * 페이지네이션 결과 슬라이스 — 컨트롤러가 DTO 로 변환할 때 사용한다. {@code Map} 으로 song lookup 을 노출해 컨트롤러가 순서 보존(Like 리스트 순서) +
     * 누락 곡 필터링을 수행하도록 한다.
     */
    public record LikePageSlice(
            List<Like> likes,
            Map<Long, Song> songsById,
            long totalCount
    ) {
    }

    /**
     * 카운터 +1. metric 이름 별로 description 을 한 곳에서 attach 한다 (registry 동일 이름 재등록 시 description 무시).
     */
    private void incrementCounter(final String metricName, final String description) {
        Counter.builder(metricName)
                .description(description)
                .register(meterRegistry)
                .increment();
    }

    /**
     * sessionId 원문 노출 금지 ({@code 04-security-policy} 익명 세션 룰).
     * SHA-256 의 hex prefix {@value #SESSION_HASH_HEX_LENGTH}자만 반환 — 원문 복원 불가, 디버깅 식별자 용도.
     * 입력이 {@code null} 이면 "unknown" 을 반환해 로그 분기 분실을 방지한다.
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
