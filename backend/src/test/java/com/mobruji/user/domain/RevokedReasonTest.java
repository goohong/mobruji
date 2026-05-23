package com.mobruji.user.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RevokedReasonTest {

    @Test
    @DisplayName("toMetricLabel: enum 명을 lowercase 로 변환한다 (observability-baseline §5-7 룰)")
    void toMetricLabel_returnsLowercaseEnumName() {
        assertThat(RevokedReason.TTL.toMetricLabel()).isEqualTo("ttl");
        assertThat(RevokedReason.USER_ROTATE.toMetricLabel()).isEqualTo("user_rotate");
        assertThat(RevokedReason.ACCOUNT_MERGE.toMetricLabel()).isEqualTo("account_merge");
    }
}
