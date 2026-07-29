package com.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

public record OrderRequest(
        @NotBlank String customerName,
        String customerPhone,
        String marketplace,
        String shippingAddressJson,
        String paymentStatus,
        BigDecimal discountAmount,
        @NotEmpty @Valid List<OrderItemRequest> items
) {
    public record OrderItemRequest(
            @NotBlank String productName,
            String sku,
            String variantName,
            @NotNull @DecimalMin("0") BigDecimal price,
            @Min(1) int quantity
    ) {}
}
