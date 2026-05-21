package com.mobruji.feedback.application;

/**
 * Like/Bookmark toggle 호출 결과. liked=true면 새로 생성, false면 기존 항목이 삭제됨.
 */
public record ToggleResult(
        boolean active,
        Long songId
) {
}
