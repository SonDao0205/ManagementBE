package com.backend.dto;

public record OrderStatusUpdateRequest(
        @jakarta.validation.constraints.NotBlank String status,
        String note
) {}
