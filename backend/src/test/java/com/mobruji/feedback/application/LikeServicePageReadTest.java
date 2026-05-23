package com.mobruji.feedback.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import com.mobruji.feedback.domain.Like;
import com.mobruji.feedback.infrastructure.LikeRepository;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * {@link LikeService#readPageBySessionId(String, int, int)} + {@code readBySessionId} 분기 회귀 가드.
 *
 * <p>{@link LikeServiceTest} 는 toggle 4분기만 cover. 본 테스트는 page-read 경로의 다음 분기를 확인한다.
 * <ul>
 * <li>totalCount=0 → early return (Pageable findBy 미호출, songRepository 미호출)</li>
 * <li>정상: likes + songsById 합성 + totalCount 반환</li>
 * <li>일부 곡 삭제: likes 는 유지되나 songsById 에서 누락 (호출 측이 totalCount 와 차이를 무시 가능)</li>
 * <li>readBySessionId: Pageable 없는 단순 위임</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class LikeServicePageReadTest {

    private static final String SESSION_ID = "session-page-read";

    @Mock
    private LikeRepository likeRepository;

    @Mock
    private SongRepository songRepository;

    private SimpleMeterRegistry meterRegistry;
    private LikeService likeService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        likeService = new LikeService(likeRepository, songRepository, meterRegistry);
    }

    @Test
    @DisplayName("readPageBySessionId: totalCount=0 → empty slice + Pageable 조회/songRepository 미호출")
    void readPageBySessionId_emptyTotal_returnsEmptySliceWithoutPageQuery() {
        // given
        given(likeRepository.countBySessionId(SESSION_ID)).willReturn(0L);

        // when
        final LikeService.LikePageSlice slice = likeService.readPageBySessionId(SESSION_ID, 0, 20);

        // then
        assertThat(slice.likes()).isEmpty();
        assertThat(slice.songsById()).isEmpty();
        assertThat(slice.totalCount()).isZero();
        verify(likeRepository, never()).findBySessionIdOrderByCreatedAtDesc(any(), any(Pageable.class));
        verify(songRepository, never()).findAllById(any());
    }

    @Test
    @DisplayName("readPageBySessionId: 정상 — likes + songsById(by id) + totalCount 합성")
    void readPageBySessionId_normal_composesLikesAndSongs() {
        // given: 2 likes, 2 songs (각각 id 매핑)
        final Like firstLike = Like.create(SESSION_ID, 10L);
        final Like secondLike = Like.create(SESSION_ID, 20L);
        final Song firstSong = mockSong(10L);
        final Song secondSong = mockSong(20L);
        given(likeRepository.countBySessionId(SESSION_ID)).willReturn(2L);
        given(likeRepository.findBySessionIdOrderByCreatedAtDesc(eq(SESSION_ID), any(Pageable.class)))
                .willReturn(List.of(firstLike, secondLike));
        given(songRepository.findAllById(List.of(10L, 20L))).willReturn(List.of(firstSong, secondSong));

        // when
        final LikeService.LikePageSlice slice = likeService.readPageBySessionId(SESSION_ID, 0, 10);

        // then
        assertThat(slice.likes()).containsExactly(firstLike, secondLike);
        assertThat(slice.songsById()).containsOnlyKeys(10L, 20L);
        assertThat(slice.songsById().get(10L)).isSameAs(firstSong);
        assertThat(slice.songsById().get(20L)).isSameAs(secondSong);
        assertThat(slice.totalCount()).isEqualTo(2L);
        verify(likeRepository, times(1)).findBySessionIdOrderByCreatedAtDesc(eq(SESSION_ID), any(Pageable.class));
    }

    @Test
    @DisplayName("readPageBySessionId: 일부 song 삭제 → likes 유지, songsById 에는 누락")
    void readPageBySessionId_someSongsDeleted_keepsLikesDropsMissingSongs() {
        // given: 3 likes, song 20L 만 살아남음
        final Like firstLike = Like.create(SESSION_ID, 10L);
        final Like secondLike = Like.create(SESSION_ID, 20L);
        final Like thirdLike = Like.create(SESSION_ID, 30L);
        final Song survivingSong = mockSong(20L);
        given(likeRepository.countBySessionId(SESSION_ID)).willReturn(3L);
        given(likeRepository.findBySessionIdOrderByCreatedAtDesc(eq(SESSION_ID), any(Pageable.class)))
                .willReturn(List.of(firstLike, secondLike, thirdLike));
        given(songRepository.findAllById(List.of(10L, 20L, 30L))).willReturn(List.of(survivingSong));

        // when
        final LikeService.LikePageSlice slice = likeService.readPageBySessionId(SESSION_ID, 0, 10);

        // then
        assertThat(slice.likes()).hasSize(3);
        assertThat(slice.songsById()).containsOnlyKeys(20L);
        assertThat(slice.totalCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("readBySessionId: Pageable 없는 단순 위임 — likeRepository 반환값 그대로")
    void readBySessionId_delegatesToRepository() {
        // given
        final Like onlyLike = Like.create(SESSION_ID, 42L);
        given(likeRepository.findBySessionIdOrderByCreatedAtDesc(SESSION_ID))
                .willReturn(List.of(onlyLike));

        // when
        final List<Like> likes = likeService.readBySessionId(SESSION_ID);

        // then
        assertThat(likes).containsExactly(onlyLike);
        verify(songRepository, never()).findAllById(any());
    }

    private static Song mockSong(final Long id) {
        final Song song = mock(Song.class);
        given(song.getId()).willReturn(id);
        return song;
    }
}
