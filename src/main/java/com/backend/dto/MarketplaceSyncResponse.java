package com.backend.dto;

import java.time.Instant;
import java.util.List;

public record MarketplaceSyncResponse(
        int accounts,
        int products,
        int variants,
        int pushedProducts,
        int pushedVariants,
        int orders,
        int orderItems,
        int archivedProducts,
        int archivedVariants,
        int failures,
        int pullFailures,
        int pushFailures,
        List<MarketplaceShopSyncResult> shopResults,
        Instant completedAt) {
}
