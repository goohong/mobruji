package com.mobruji.feedback.api.dto;

import java.time.LocalDateTime;

import com.mobruji.feedback.domain.Like;
import com.mobruji.song.api.dto.SongResponse;
import com.mobruji.song.domain.Song;

/**
 * 좋아요 단건 + 해당 곡 메타데이터 결합 응답.
 *
 * <p>spec {@code recommendation-history-and-feedback.md §5-2} — fe `/likes` 페이지가 곡 카드를 즉시 렌더하기 위해 필요한 정보(title, artist,
 * difficulty, lowMidi/highMidi 등)를 N+1 호출 없이 한 번에
 * 전달한다.
 *
 * <p>{@link Like} 엔티티는 ID-only 참조(ADR-0005 §A-7)이므로 서비스 레이어에서 별도로 {@code Song} 을
 * 조회해 본 DTO 로 합친다.
 */
public record LikeWithSongResponse(
        Long id,
        SongResponse song,
        LocalDateTime likedAt
) {

    public static LikeWithSongResponse from(final Like like, final Song song) {
        return new LikeWithSongResponse(like.getId(), SongResponse.from(song), like.getCreatedAt());
    }
}
