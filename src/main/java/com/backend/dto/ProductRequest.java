package com.backend.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProductRequest(

        @NotBlank
        @Size(max = 255)
        String name,

        @Size(max = 100)
        String productCode,

        @Size(max = 100)
        String category,

        String description,

        @DecimalMin("0")
        BigDecimal price,

        @DecimalMin("0")
        BigDecimal costPrice,

        @Min(0)
        Integer totalStock,

        @Min(0)
        Integer minStockAlert,

        String imageUrl,

        /** Nullable – service defaults to ACTIVE when null. */
        String status,

        @Valid
        List<VariantRequest> variants) {

    public record VariantRequest(
            String sku,
            String variantName,
            BigDecimal price,
            Integer stockQuantity) {
    }
}
