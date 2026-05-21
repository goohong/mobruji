package com.mobruji.feedback.application;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mobruji.feedback.domain.Like;
import com.mobruji.feedback.infrastructure.LikeRepository;
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
}
