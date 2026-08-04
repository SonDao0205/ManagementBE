package com.backend.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ProductResponse(
        String id,
        String tenantId,
        String name,
        String productCode,
        String category,
        String description,
        BigDecimal price,
        BigDecimal costPrice,
        Integer totalStock,
        Integer minStockAlert,
        String imageUrl,
        String status,
        Instant createdAt,
        Instant updatedAt,
        List<VariantResponse> variants,
        List<ProductMediaResponse> media,
        List<String> marketplaceAccountIds) {

    public record VariantResponse(
            String id,
            String sku,
            String variantName,
            BigDecimal price,
            Integer stockQuantity,
            Integer reservedStock,
            Integer availableStock) {
    }
}
