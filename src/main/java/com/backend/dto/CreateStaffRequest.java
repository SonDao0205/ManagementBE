package com.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateStaffRequest(
        @NotBlank(message = "Vui lòng nhập email.")
        @Size(max = 255, message = "Email không được vượt quá 255 ký tự.")
        @Pattern(
                regexp = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$",
                message = "Email không đúng định dạng.")
        String email,

        @NotBlank(message = "Vui lòng nhập họ và tên.")
        @Size(min = 2, max = 50, message = "Họ tên phải có độ dài từ 2 đến 50 ký tự.")
        String displayName,

        @Pattern(
                regexp = "^$|^(0[3|5|7|8|9])+([0-9]{8})$",
                message = "Số điện thoại không đúng định dạng Việt Nam.")
        String phoneNumber,

        @NotBlank(message = "Vui lòng nhập mật khẩu.")
        @Size(min = 6, max = 100, message = "Mật khẩu phải chứa ít nhất 6 ký tự.")
        String password
) {}
