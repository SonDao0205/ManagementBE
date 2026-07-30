package com.backend.dto;

import jakarta.validation.constraints.NotBlank;

public record MergeCustomersRequest(
        @NotBlank(message = "Vui lòng chọn khách hàng nguồn cần gộp.")
        String sourceCustomerId,

        @NotBlank(message = "Vui lòng chọn khách hàng đích để giữ lại.")
        String targetCustomerId,

        String selectedDisplayName,
        String selectedPhone,
        String selectedEmail
) {}
