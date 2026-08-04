package com.backend.dto;

import java.util.List;

public record MarketplaceSyncRequest(
        List<String> productIds,
        List<String> marketplaceAccountIds,
        boolean allProducts) {
}
