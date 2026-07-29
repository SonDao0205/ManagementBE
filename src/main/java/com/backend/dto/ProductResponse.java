package com.backend.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.backend.entity.ProductEntity;
import com.backend.entity.ProductVariantEntity;

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
        List<VariantResponse> variants) {

    public record VariantResponse(
            String id,
            String sku,
            String variantName,
            BigDecimal price,
            Integer stockQuantity) {
    }

    public static ProductResponse from(ProductEntity p, List<ProductVariantEntity> variantEntities) {
        List<VariantResponse> variants = variantEntities == null ? List.of() : variantEntities.stream()
                .map(v -> new VariantResponse(
                        v.getId(),
                        v.getSku(),
                        v.getVariantName(),
                        v.getPrice(),
                        v.getStockQuantity()))
                .toList();

        return new ProductResponse(
                p.getId(),
                p.getTenantId(),
                p.getName(),
                p.getProductCode(),
                p.getCategory(),
                p.getDescription(),
                p.getPrice(),
                p.getCostPrice(),
                p.getTotalStock(),
                p.getMinStockAlert(),
                p.getImageUrl(),
                p.getStatus(),
                p.getCreatedAt(),
                variants);
    }
}
