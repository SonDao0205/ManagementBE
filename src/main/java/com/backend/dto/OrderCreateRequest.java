package com.backend.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record OrderCreateRequest(
        @NotBlank @Size(max = 255) String customerName,
        @Pattern(regexp = "^$|^[0-9+() .-]{8,30}$", message = "Số điện thoại không hợp lệ")
        String customerPhone,
        @Pattern(regexp = "PAID|UNPAID", message = "Trạng thái thanh toán không hợp lệ")
        String paymentStatus,
        @NotNull @DecimalMin("0.0") BigDecimal discountAmount,
        @NotBlank String shippingAddressJson,
        @NotEmpty List<@Valid OrderCreateItemRequest> items) {

    public record OrderCreateItemRequest(
            @NotBlank @Size(max = 500) String productName,
            @Size(max = 200) String sku,
            @Size(max = 255) String variantName,
            @NotNull @DecimalMin("0.0") BigDecimal price,
            @Positive int quantity) {
    }
}
