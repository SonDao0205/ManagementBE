package com.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AiRejectionRequest(
        @NotBlank @Size(min = 3, max = 1000) String reason) {
}
