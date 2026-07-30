package com.backend.dto;

import java.util.List;

public record ProductSyncResponse(
        int productCount,
        int marketplaceCount,
        int importedCount,
        int successCount,
        int errorCount,
        List<ProductSyncItemResponse> results) {

    public record ProductSyncItemResponse(
            String productId,
            String productName,
            String marketplace,
            String marketplaceAccountId,
            boolean success,
            String externalProductId,
            String error) {
    }
}
