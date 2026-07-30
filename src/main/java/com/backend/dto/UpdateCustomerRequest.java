package com.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateCustomerRequest(
        @NotBlank(message = "Tên khách hàng không được để trống.")
        @Size(max = 255, message = "Tên khách hàng không được vượt quá 255 ký tự.")
        String displayName,

        @Pattern(
                regexp = "^$|^(0|\\+84)[3|5|7|8|9]+[0-9]{8}$",
                message = "Số điện thoại không đúng định dạng.")
        String phoneNumber,

        @Pattern(
                regexp = "^$|^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$",
                message = "Email không đúng định dạng.")
        String email
) {}
