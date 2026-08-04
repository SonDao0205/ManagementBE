package com.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AiSuggestionRequest(
        @NotBlank @Size(max = 36) String triggerMessageId,
        @NotBlank @Size(min = 8, max = 160) String requestId) {
}
