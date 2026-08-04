package com.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AiConversationModeRequest(
        @NotBlank @Pattern(regexp = "AUTO|SUGGEST_ONLY|HUMAN_ONLY") String mode) {
}
