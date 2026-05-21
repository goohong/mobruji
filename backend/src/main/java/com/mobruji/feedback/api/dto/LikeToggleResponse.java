package com.mobruji.feedback.api.dto;

import com.mobruji.feedback.application.ToggleResult;

/**
 * Like toggle 응답. liked=true면 새로 좋아요가 생성됐고, false면 기존 좋아요가 삭제됐다.
 */
public record LikeToggleResponse(
        boolean liked,
        Long songId
) {

    public static LikeToggleResponse from(final ToggleResult toggleResult) {
        return new LikeToggleResponse(toggleResult.active(), toggleResult.songId());
    }
}
