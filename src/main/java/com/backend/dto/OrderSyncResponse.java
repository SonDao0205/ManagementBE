package com.backend.dto;

import java.util.List;

public record OrderSyncResponse(
        int marketplaceCount,
        int synchronizedOrderCount,
        int failedMarketplaceCount,
        List<String> errors) {
}
