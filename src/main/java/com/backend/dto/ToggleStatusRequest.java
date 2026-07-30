package com.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ToggleStatusRequest(
        @NotBlank(message = "Trạng thái không được để trống.")
        @Pattern(regexp = "^(ACTIVE|LOCKED)$", message = "Trạng thái không hợp lệ.")
        String status
) {}
