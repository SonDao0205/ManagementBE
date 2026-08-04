package com.backend.dto;

public record AiShopKnowledgeStatusResponse(
        String marketplaceAccountId,
        String status,
        String catalogVersion,
        int productCount,
        int variantCount,
        int missingColorCount,
        int missingSizeCount,
        int indexedPoints,
        String cacheStatus,
        String vectorStatus,
        String lastBuiltAt,
        String lastIndexedAt,
        String lastError) {
}
