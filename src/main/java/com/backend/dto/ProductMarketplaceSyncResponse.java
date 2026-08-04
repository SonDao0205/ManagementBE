package com.backend.dto;

public record ProductMarketplaceSyncResponse(
        int products,
        int shops,
        int queuedMappings) {
}
