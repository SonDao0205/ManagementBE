package com.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank(message = "Mật khẩu không được để trống.")
        @Size(min = 6, max = 100, message = "Mật khẩu phải chứa ít nhất 6 ký tự.")
        String password
) {}
