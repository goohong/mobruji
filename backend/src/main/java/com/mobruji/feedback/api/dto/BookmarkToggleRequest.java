package com.mobruji.feedback.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record BookmarkToggleRequest(
        @NotBlank @Size(max = 64) String sessionId,
        @NotNull @Positive Long songId
) {
}
