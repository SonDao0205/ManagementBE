package com.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateStaffRequest(
        @NotBlank(message = "Vui lòng nhập họ và tên.")
        @Size(min = 2, max = 50, message = "Họ tên phải có độ dài từ 2 đến 50 ký tự.")
        String displayName,

        @Pattern(
                regexp = "^$|^(0[3|5|7|8|9])+([0-9]{8})$",
                message = "Số điện thoại không đúng định dạng Việt Nam.")
        String phoneNumber,

        @Pattern(
                regexp = "^(ACTIVE|LOCKED)$",
                message = "Trạng thái không hợp lệ.")
        String status
) {}
