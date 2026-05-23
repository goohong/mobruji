package com.mobruji.recommendation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * RecommendationNotFoundException 회귀 가드.
 *
 * <p>HTTP 404 매핑은 {@link ResponseStatus} 메타데이터에 의존하므로 어노테이션이
 * 사라지면 응답 코드가 500으로 회귀한다. id 노출 메시지도 클라이언트 디버깅용
 * 계약이므로 함께 고정한다.
 */
class RecommendationNotFoundExceptionTest {

    @Test
    @DisplayName("메시지에 id가 포함된다")
    void message_containsId() {
        // given
        final long missingId = 4242L;

        // when
        final RecommendationNotFoundException exception = new RecommendationNotFoundException(missingId);

        // then
        assertThat(exception)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("4242");
    }

    @Test
    @DisplayName("@ResponseStatus(NOT_FOUND) 메타데이터가 보존된다")
    void responseStatus_isNotFound() {
        // given
        final ResponseStatus annotation = RecommendationNotFoundException.class.getAnnotation(ResponseStatus.class);

        // then
        assertThat(annotation)
                .as("RecommendationNotFoundException must keep @ResponseStatus for 404 mapping")
                .isNotNull();
        assertThat(annotation.value()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
