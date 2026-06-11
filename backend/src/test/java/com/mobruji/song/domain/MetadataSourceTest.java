package com.mobruji.song.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link MetadataSource} enum 회귀 가드.
 *
 * <p>DB(`metadata_source` 컬럼)에 enum name이 저장되어, name 변경은 기존 데이터 호환성을 깬다.
 * 특히 {@code AUDIO_ANALYSIS}는 audio-tooling-bootstrap spec PR C(confidence ≥ 0.6 통과 시 backfill)의
 * 식별자이므로 별도로 가드한다.
 */
class MetadataSourceTest {

    @Test
    @DisplayName("5개 source가 정의되어 있다")
    void values_containsExactlyFiveSources() {
        assertThat(MetadataSource.values())
                .hasSize(5)
                .containsExactlyInAnyOrder(
                        MetadataSource.MANUAL_SEED,
                        MetadataSource.EXTERNAL_API,
                        MetadataSource.USER_CONTRIBUTION,
                        MetadataSource.INFERRED,
                        MetadataSource.AUDIO_ANALYSIS);
    }

    @Test
    @DisplayName("AUDIO_ANALYSIS source가 존재한다 (audio-tooling-bootstrap PR C 식별자)")
    void audioAnalysis_isDefined() {
        assertThat(MetadataSource.valueOf("AUDIO_ANALYSIS")).isEqualTo(MetadataSource.AUDIO_ANALYSIS);
    }

    @Test
    @DisplayName("MANUAL_SEED source가 존재한다 (시드 적재 식별자)")
    void manualSeed_isDefined() {
        assertThat(MetadataSource.valueOf("MANUAL_SEED")).isEqualTo(MetadataSource.MANUAL_SEED);
    }
}
