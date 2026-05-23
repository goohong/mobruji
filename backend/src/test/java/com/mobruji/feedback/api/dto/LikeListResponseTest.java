package com.mobruji.feedback.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.feedback.domain.Like;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;

/**
 * {@link LikeListResponse} wrapper 회귀 가드.
 *
 * <p>{@code LikeListResponse} 는 별도 {@code from(...)} factory 가 없고 {@code LikeController#readBySessionId}
 * 에서 직접 생성된다. 그러나 fe 가 의존하는 필드명/순서/페이지네이션 의미(hasNext 등)는 wrapper 가 보존해야 하므로
 * 본 테스트는 record component 매핑 + 빈 리스트 처리 + 입력 순서 보존을 잠근다 (spec
 * recommendation-history-and-feedback.md §5-2).
 */
class LikeListResponseTest {

    @Test
    @DisplayName("정상 구성: responses + page/size/totalCount/hasNext 매핑 그대로 보존")
    void preservesAllFields() {
        // given
        final LikeWithSongResponse first = sampleLikeWithSongResponse(10L, "벚꽃 엔딩");
        final LikeWithSongResponse second = sampleLikeWithSongResponse(11L, "취중진담");

        // when
        final LikeListResponse likeListResponse = new LikeListResponse(
                List.of(first, second), 0, 20, 2L, false);

        // then
        assertThat(likeListResponse.responses()).hasSize(2);
        assertThat(likeListResponse.responses()).containsExactly(first, second);
        assertThat(likeListResponse.page()).isEqualTo(0);
        assertThat(likeListResponse.size()).isEqualTo(20);
        assertThat(likeListResponse.totalCount()).isEqualTo(2L);
        assertThat(likeListResponse.hasNext()).isFalse();
    }

    @Test
    @DisplayName("빈 리스트도 wrapper 로 감쌀 수 있다 (fe 가 키 명을 기대하므로 null 이 아닌 빈 리스트로 노출)")
    void emptyList_wrappedSafely() {
        // given / when
        final LikeListResponse likeListResponse = new LikeListResponse(List.of(), 0, 20, 0L, false);

        // then
        assertThat(likeListResponse.responses()).isEmpty();
        assertThat(likeListResponse.totalCount()).isZero();
        assertThat(likeListResponse.hasNext()).isFalse();
    }

    @Test
    @DisplayName("hasNext=true: 다음 페이지가 존재하는 경우 그대로 노출 (계산은 controller 책임)")
    void hasNextTrue_preserved() {
        // given
        final LikeWithSongResponse only = sampleLikeWithSongResponse(1L, "t");

        // when
        final LikeListResponse likeListResponse = new LikeListResponse(
                List.of(only), 0, 1, 5L, true);

        // then
        assertThat(likeListResponse.hasNext()).isTrue();
        assertThat(likeListResponse.totalCount()).isEqualTo(5L);
    }

    @Test
    @DisplayName("record equals/hashCode: 내용이 같으면 동일한 wrapper 로 간주")
    void recordEquality() {
        // given
        final LikeWithSongResponse single = sampleLikeWithSongResponse(1L, "t");

        // when
        final LikeListResponse a = new LikeListResponse(List.of(single), 0, 20, 1L, false);
        final LikeListResponse b = new LikeListResponse(List.of(single), 0, 20, 1L, false);

        // then
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    private LikeWithSongResponse sampleLikeWithSongResponse(final Long likeId, final String title) {
        final Song song = Song.builder()
                .title(title).artist("artist")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
        final Like like = Like.create("session", 1L);
        // record component likedAt 만 사용 — likeId 는 wrapper 동일성 검증의 식별자로만 활용
        final LikeWithSongResponse response = LikeWithSongResponse.from(like, song);
        // 명시적 id 매핑이 필요 없는 케이스는 record 그대로 사용 (likeId 인자는 가독성 목적)
        return new LikeWithSongResponse(likeId, response.song(),
                response.likedAt() != null ? response.likedAt() : LocalDateTime.now());
    }
}
