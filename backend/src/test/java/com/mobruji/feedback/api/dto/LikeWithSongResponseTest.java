package com.mobruji.feedback.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.feedback.domain.Like;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;

/**
 * {@link LikeWithSongResponse#from(Like, Song)} factory 회귀 가드.
 *
 * <p>spec recommendation-history-and-feedback.md §5-2 — like + song meta 결합 응답이 fe 곡 카드 렌더에 필수다.
 * factory 가 song 매핑을 빼먹거나 likedAt 을 createdAt 이 아닌 다른 필드에서 가져오면 회귀.
 */
class LikeWithSongResponseTest {

    @Test
    @DisplayName("from: Like + Song 결합 — song 메타 + likedAt(createdAt) 모두 매핑")
    void from_combinesLikeAndSongMeta() {
        // given
        final Song song = Song.builder()
                .title("벚꽃 엔딩").artist("버스커 버스커")
                .keyOriginal(MusicalKey.A_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .lowMidi(60).highMidi(76)
                .build();
        final Like like = Like.create("session-a", 100L);

        // when
        final LikeWithSongResponse likeWithSongResponse = LikeWithSongResponse.from(like, song);

        // then
        assertThat(likeWithSongResponse.song()).isNotNull();
        assertThat(likeWithSongResponse.song().title()).isEqualTo("벚꽃 엔딩");
        assertThat(likeWithSongResponse.song().artist()).isEqualTo("버스커 버스커");
        assertThat(likeWithSongResponse.song().lowMidi()).isEqualTo(60);
        assertThat(likeWithSongResponse.song().highMidi()).isEqualTo(76);
        assertThat(likeWithSongResponse.likedAt()).isEqualTo(like.getCreatedAt());
    }

    @Test
    @DisplayName("from: Song 메타 누락(lowMidi/highMidi null) — song.noteName 도 null 로 전달")
    void from_whenSongMetaPartiallyNull_propagatesNulls() {
        // given
        final Song song = Song.builder()
                .title("t").artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
        final Like like = Like.create("session-b", 1L);

        // when
        final LikeWithSongResponse likeWithSongResponse = LikeWithSongResponse.from(like, song);

        // then
        assertThat(likeWithSongResponse.song().lowMidi()).isNull();
        assertThat(likeWithSongResponse.song().highMidi()).isNull();
        assertThat(likeWithSongResponse.song().lowestNoteName()).isNull();
        assertThat(likeWithSongResponse.song().highestNoteName()).isNull();
        assertThat(likeWithSongResponse.likedAt()).isEqualTo(like.getCreatedAt());
    }
}
