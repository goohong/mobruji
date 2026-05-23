package com.mobruji.recommendation.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobruji.recommendation.domain.RecommendationRequestEntity;
import com.mobruji.recommendation.domain.RecommendationResult;
import com.mobruji.song.domain.Mood;

/**
 * {@link RecommendationHistoryResponse} 가 의도적으로 {@code excludeSongIds} 키를
 * 노출하지 않는다는 결정을 잠그는 회귀 가드.
 *
 * <p>배경: #218 PR 로 entity 측 excludeSongIds 가 영속되지만, history 응답 payload 는
 * fe 가 사용하지 않으므로 record 필드에서 의도적으로 제외했다 (sessionId echo 제거(#423)
 * 와 동일한 dead-payload 제거 정책). 추후 DTO refactor 가 우연히 excludeSongIds 를
 * 노출하면 본 가드가 깨져 결정의 변경을 가시화한다.
 *
 * <p>spec: {@code docs/features/recommendation-history-and-feedback.md} §5-2.
 *
 * <p>현재 응답 매핑·필드 자체의 정합성은
 * {@link RecommendationHistoryResponseTest} 가 커버하므로 본 파일은 "미노출" 만 다룬다.
 */
class RecommendationHistoryResponseExcludeSongIdsTest {

    private static final String EXCLUDE_FIELD_NAME = "excludeSongIds";

    @Test
    @DisplayName("RecommendationHistoryResponse record 의 component 셋에 excludeSongIds 가 없다")
    void responseRecord_doesNotDeclareExcludeSongIds() {
        // given / when
        final List<String> componentNames = Arrays.stream(RecommendationHistoryResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        // then: 의도된 미노출. 추후 노출이 필요해 추가되더라도 이 테스트가 깨지면서 결정 변경이 가시화된다.
        assertThat(componentNames).doesNotContain(EXCLUDE_FIELD_NAME);
    }

    @Test
    @DisplayName("from 으로 매핑된 DTO 의 Jackson 직렬화 JSON 에 excludeSongIds 키가 등장하지 않는다")
    void jsonSerialization_omitsExcludeSongIdsKey() throws Exception {
        // given: entity 에는 excludeSongIds 가 보존된 상태.
        final RecommendationRequestEntity recommendationRequestEntity = RecommendationRequestEntity.create(
                "session-omit", 48, 72, Mood.UPBEAT, null, List.of(101L, 202L));
        final RecommendationResult recommendationResult = new RecommendationResult(50L, List.of());
        final RecommendationHistoryResponse recommendationHistoryResponse = RecommendationHistoryResponse.from(
                recommendationRequestEntity, recommendationResult);

        // when: LocalDateTime(requestedAt) 직렬화를 위해 jsr310 module 자동 등록.
        final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        final String json = objectMapper.writeValueAsString(recommendationHistoryResponse);

        // then: 키 자체가 직렬화되지 않는다 (record 미선언 필드는 Jackson 출력 대상 아님).
        assertThat(json).doesNotContain("\"" + EXCLUDE_FIELD_NAME + "\"");
    }
}
