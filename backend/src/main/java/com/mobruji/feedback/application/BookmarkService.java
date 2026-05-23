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

import com.mobruji.feedback.domain.Bookmark;
import com.mobruji.feedback.infrastructure.BookmarkRepository;
import com.mobruji.song.domain.Song;
import com.mobruji.song.domain.SongNotFoundException;
import com.mobruji.song.infrastructure.SongRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Bookmark toggle application service.
 *
 * <p>spec: {@code docs/features/recommendation-history-and-feedback.md} §3 비기능 — toggle 분기 직후
 * {@link #METRIC_BOOKMARK_CREATED} / {@link #METRIC_BOOKMARK_DELETED} 카운터 +1. 메트릭 이름은
 * {@code docs/features/observability-baseline.md} §5-3 표가 단일 진실
 * (prefix {@code mobruji.recommendationhistory.*}).
 *
 * <p>로깅 정책: sessionId 원문 노출 금지 ({@code docs/ai-harness/04-security-policy.md} 익명 세션 룰).
 * SHA-256 hex prefix({@value #SESSION_HASH_HEX_LENGTH}자)만 노출 — {@link LikeService} 와 동일 패턴.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class BookmarkService {

    /**
     * Micrometer counter — 북마크 등록 1건당 +1.
     * spec {@code docs/features/observability-baseline.md} §5-3 표 단일 진실.
     */
    static final String METRIC_BOOKMARK_CREATED = "mobruji.recommendationhistory.bookmark.created";

    /**
     * Micrometer counter — 북마크 취소 1건당 +1.
     * spec {@code docs/features/observability-baseline.md} §5-3 표 단일 진실.
     */
    static final String METRIC_BOOKMARK_DELETED = "mobruji.recommendationhistory.bookmark.deleted";

    /** sessionId 로그 노출 시 사용하는 SHA-256 hex prefix 길이. */
    private static final int SESSION_HASH_HEX_LENGTH = 12;

    private static final String HASH_ALGORITHM = "SHA-256";

    private final BookmarkRepository bookmarkRepository;
    private final SongRepository songRepository;
    private final MeterRegistry meterRegistry;

    public ToggleResult toggle(final String sessionId, final Long songId) {
        if (!songRepository.existsById(songId)) {
            log.warn(
                    "event=feedback.bookmark.toggle.song_not_found session_hash={} song_id={}",
                    hashSessionId(sessionId), songId);
            throw new SongNotFoundException(songId);
        }
        if (bookmarkRepository.existsBySessionIdAndSongId(sessionId, songId)) {
            bookmarkRepository.deleteBySessionIdAndSongId(sessionId, songId);
            incrementCounter(METRIC_BOOKMARK_DELETED,
                    "북마크 취소 1건 누적 (spec: recommendation-history-and-feedback §3 비기능)");
            log.info(
                    "event=feedback.bookmark.deleted session_hash={} song_id={}",
                    hashSessionId(sessionId), songId);
            return new ToggleResult(false, songId);
        }
        bookmarkRepository.save(Bookmark.create(sessionId, songId));
        incrementCounter(METRIC_BOOKMARK_CREATED, "북마크 등록 1건 누적 (spec: recommendation-history-and-feedback §3 비기능)");
        log.info(
                "event=feedback.bookmark.created session_hash={} song_id={}",
                hashSessionId(sessionId), songId);
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
