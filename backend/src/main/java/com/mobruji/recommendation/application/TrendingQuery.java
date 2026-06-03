package com.mobruji.recommendation.application;

import com.mobruji.song.domain.Mood;

/**
 * 트렌딩 조회 유스케이스의 입력 커맨드 (#1488). api.dto 의존을 끊기 위해 application 계층에 둔 입력 모델
 * (ADR 0005 §A-7). 검증/기본값 적용은 api.dto 에서 수행하고, 커맨드는 정규화된 값만 담는다.
 *
 * <ul>
 * <li>{@code periodDays} — 집계 기간(일). 호출 시점 기준 최근 N일 추천 결과만 집계.</li>
 * <li>{@code mood} — nullable. 값이 있으면 해당 분위기 요청만 집계 (노래방 일반 차트와 차별).</li>
 * <li>{@code voiceRangeLow}/{@code voiceRangeHigh} — nullable(둘 다 있거나 둘 다 없음). 값이 있으면 요청 음역대와
 * 구간 overlap 하는 추천만 집계해 "내 음역대에서 뜨는 곡"을 본다.</li>
 * <li>{@code limit} — 반환할 인기곡 최대 개수.</li>
 * </ul>
 */
public record TrendingQuery(
        int periodDays,
        Mood mood,
        Integer voiceRangeLow,
        Integer voiceRangeHigh,
        int limit
) {

    public TrendingQuery {
        if (periodDays < 1) {
            throw new IllegalArgumentException("periodDays must be >= 1: " + periodDays);
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1: " + limit);
        }
        if ((voiceRangeLow == null) != (voiceRangeHigh == null)) {
            throw new IllegalArgumentException(
                    "voiceRangeLow and voiceRangeHigh must be both present or both absent");
        }
        if (voiceRangeLow != null && voiceRangeLow > voiceRangeHigh) {
            throw new IllegalArgumentException(
                    "voiceRangeLow (" + voiceRangeLow + ") must be <= voiceRangeHigh (" + voiceRangeHigh + ")");
        }
    }
}
