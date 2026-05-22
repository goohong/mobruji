package com.mobruji.feedback.application;

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

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class LikeService {

    private final LikeRepository likeRepository;
    private final SongRepository songRepository;

    /**
     * 좋아요 토글. 이미 존재하면 삭제 → liked=false, 없으면 생성 → liked=true.
     *
     * <p>존재하지 않는 songId면 404(SongNotFoundException). 같은 (session, song) 동시 요청은
     * unique constraint로 막힌다.
     */
    public ToggleResult toggle(final String sessionId, final Long songId) {
        if (!songRepository.existsById(songId)) {
            throw new SongNotFoundException(songId);
        }
        if (likeRepository.existsBySessionIdAndSongId(sessionId, songId)) {
            likeRepository.deleteBySessionIdAndSongId(sessionId, songId);
            return new ToggleResult(false, songId);
        }
        likeRepository.save(Like.create(sessionId, songId));
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
}
