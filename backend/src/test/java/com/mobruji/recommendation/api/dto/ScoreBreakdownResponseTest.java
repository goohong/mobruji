package com.mobruji.recommendation.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobruji.recommendation.domain.ScoreBreakdown;

class ScoreBreakdownResponseTest {

    /**
     * fe 14 matchReason 펼침 UX가 의존하는 6개 신호 필드 이름. 변경 시 fe 호환성 회귀 — 본 테스트로 wire format을 잠근다.
     */
    private static final Set<String> EXPECTED_FIELDS = Set.of(
            "keyMatch", "rangeFit", "genreMatch", "moodMatch", "popularity", "tempoMatch");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("from: domain ScoreBreakdown의 6신호를 그대로 DTO로 매핑한다 (v2 #218)")
    void from_mapsAllFields() {
        final ScoreBreakdown breakdown = new ScoreBreakdown(1.0, 0.8, 0.0, 1.0, 1.0, 0.6);

        final ScoreBreakdownResponse response = ScoreBreakdownResponse.from(breakdown);

        assertThat(response.keyMatch()).isEqualTo(1.0);
        assertThat(response.rangeFit()).isEqualTo(0.8);
        assertThat(response.genreMatch()).isEqualTo(0.0);
        assertThat(response.moodMatch()).isEqualTo(1.0);
        assertThat(response.popularity()).isEqualTo(1.0);
        assertThat(response.tempoMatch()).isEqualTo(0.6);
    }

    @Test
    @DisplayName("from: null 입력(과거 추천 재조회 경로)은 null 반환 — 응답에 breakdown 필드를 그대로 null로 노출")
    void from_null_returnsNull() {
        assertThat(ScoreBreakdownResponse.from(null)).isNull();
    }

    @Test
    @DisplayName("JSON 직렬화: 6신호 필드가 모두 정확한 이름(camelCase)으로 노출된다 — fe 14 펼침 호환성 가드")
    void serializesAllSixFieldsWithExpectedNames() throws Exception {
        final ScoreBreakdownResponse response = new ScoreBreakdownResponse(1.0, 0.8, 0.0, 1.0, 1.0, 0.6);

        final String json = objectMapper.writeValueAsString(response);
        final JsonNode node = objectMapper.readTree(json);

        assertThat(node.isObject()).isTrue();
        EXPECTED_FIELDS.forEach(field -> assertThat(node.has(field))
                .as("JSON에 '%s' 필드가 존재해야 한다 — fe 펼침 UX 필드명 의존", field)
                .isTrue());
        assertThat(node.get("keyMatch").asDouble()).isEqualTo(1.0);
        assertThat(node.get("rangeFit").asDouble()).isEqualTo(0.8);
        assertThat(node.get("genreMatch").asDouble()).isEqualTo(0.0);
        assertThat(node.get("moodMatch").asDouble()).isEqualTo(1.0);
        assertThat(node.get("popularity").asDouble()).isEqualTo(1.0);
        assertThat(node.get("tempoMatch").asDouble()).isEqualTo(0.6);
    }

    @Test
    @DisplayName("JSON 직렬화: 기대하지 않은 extra 필드는 없다 — 스키마 안정성")
    void serializesNoExtraFields() throws Exception {
        final ScoreBreakdownResponse response = new ScoreBreakdownResponse(0.5, 0.5, 0.5, 0.5, 0.5, 0.5);

        final JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(response));

        final Set<String> actualFields = java.util.stream.StreamSupport
                .stream(java.util.Spliterators.spliteratorUnknownSize(node.fieldNames(), 0), false)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        assertThat(actualFields).isEqualTo(EXPECTED_FIELDS);
    }

    @Test
    @DisplayName("JSON 직렬화: 0.0 값도 누락 없이 포함된다 — null과 0.0의 wire-level 구분")
    void serializesZeroValuesExplicitly() throws Exception {
        final ScoreBreakdownResponse response = new ScoreBreakdownResponse(0.0, 0.0, 0.0, 0.0, 0.0, 0.0);

        final JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(response));

        EXPECTED_FIELDS.forEach(field -> {
            assertThat(node.has(field)).as("0.0도 키가 존재해야 한다: %s", field).isTrue();
            assertThat(node.get(field).isNull()).as("0.0은 JSON null이 아니다: %s", field).isFalse();
            assertThat(node.get(field).asDouble()).as("값은 0.0: %s", field).isEqualTo(0.0);
        });
    }

    @Test
    @DisplayName("JSON round-trip: 직렬화 → 역직렬화 결과가 원본과 동등 (DTO 호환성)")
    void jsonRoundTripPreservesAllFields() throws Exception {
        final ScoreBreakdownResponse original = new ScoreBreakdownResponse(0.1, 0.2, 0.3, 0.4, 0.5, 0.6);

        final String json = objectMapper.writeValueAsString(original);
        final ScoreBreakdownResponse restored = objectMapper.readValue(json, ScoreBreakdownResponse.class);

        assertThat(restored).isEqualTo(original);
    }

    @Test
    @DisplayName("JSON 직렬화: from(null)이 반환한 null을 container에 임베드하면 JSON null로 노출된다")
    void nullBreakdownSerializesAsJsonNullInContainer() throws Exception {
        final ScoreBreakdownResponse nullBreakdown = ScoreBreakdownResponse.from(null);

        // container 시뮬레이션 — 실제 추천 응답에서 breakdown은 nullable 필드로 임베드된다.
        final java.util.Map<String, ScoreBreakdownResponse> container = new java.util.HashMap<>();
        container.put("breakdown", nullBreakdown);

        final JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(container));

        assertThat(node.has("breakdown")).isTrue();
        assertThat(node.get("breakdown").isNull()).isTrue();
    }
}
