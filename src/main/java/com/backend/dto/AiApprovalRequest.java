package com.backend.dto;

import jakarta.validation.constraints.Size;

public record AiApprovalRequest(
        @Size(min = 1, max = 8000) String correctedText,
        boolean send) {
}
