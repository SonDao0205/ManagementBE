package com.backend.dto;

import java.time.Instant;

public record MarketplaceShopSyncResult(
        String accountId,
        String marketplace,
        String externalAccountId,
        String shopName,
        String status,
        String pullStatus,
        String pushStatus,
        int products,
        int variants,
        int pushedProducts,
        int pushedVariants,
        int orders,
        int orderItems,
        int archivedProducts,
        int archivedVariants,
        String errorCode,
        String errorMessage,
        String pullErrorMessage,
        String pushErrorMessage,
        Instant completedAt) {
}
