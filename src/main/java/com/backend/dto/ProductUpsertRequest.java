package com.backend.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record ProductUpsertRequest(
        @NotBlank @Size(max = 500) String name,
        @NotBlank @Size(max = 100) String productCode,
        @Size(max = 100) String category,
        String description,
        @PositiveOrZero BigDecimal price,
        @PositiveOrZero BigDecimal costPrice,
        @PositiveOrZero Integer totalStock,
        @PositiveOrZero Integer minStockAlert,
        @Size(max = 2000) String imageUrl,
        @Size(max = 20) String status,
        List<@Valid ProductVariantRequest> variants,
        List<@Size(max = 30) String> marketplaces) {

    public record ProductVariantRequest(
            @NotBlank @Size(max = 200) String sku,
            @Size(max = 255) String variantName,
            @PositiveOrZero BigDecimal price,
            @PositiveOrZero Integer stockQuantity) {
    }
}
