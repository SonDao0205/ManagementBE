package com.backend.dto;

import java.time.Instant;

public record ProductMediaResponse(
        String id,
        String mediaType,
        String storageKey,
        String publicUrl,
        Integer sortOrder,
        boolean primary,
        String productVariantId,
        Instant createdAt
) {
}
