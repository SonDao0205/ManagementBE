package com.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record TenantLoginRequest(
        @NotBlank(message = "Vui lòng nhập email.")
        @Size(max = 255, message = "Email không được vượt quá 255 ký tự.")
        @Pattern(
                regexp = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$",
                message = "Email không đúng định dạng.")
        String email,
        @NotBlank(message = "Vui lòng nhập mật khẩu.")
        @Size(max = 200, message = "Mật khẩu không được vượt quá 200 ký tự.")
        String password) {
}
