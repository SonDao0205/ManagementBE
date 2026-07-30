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
        int totalStock,
        int minStockAlert,
        String imageUrl,
        String status,
        Instant createdAt,
        List<ProductVariantResponse> variants,
        List<String> marketplaces) {

    public record ProductVariantResponse(
            String id,
            String sku,
            String variantName,
            BigDecimal price,
            int stockQuantity) {
    }
}
