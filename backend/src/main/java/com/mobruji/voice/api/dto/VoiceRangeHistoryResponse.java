package com.mobruji.voice.api.dto;

import java.util.List;

/**
 * 세션별 음역 측정 시계열 응답 wrapper.
 *
 * <p>spec: docs/features/voice-range-progress.md §5-2 — 향후 페이지네이션/메타 추가 시 wrapper 객체에 필드를 더하기 위해 배열을 직접 노출하지 않고 객체로
 * 감싼다.
 */
public record VoiceRangeHistoryResponse(List<VoiceRangeSnapshotResponse> voiceRangeSnapshotResponses) {
}
