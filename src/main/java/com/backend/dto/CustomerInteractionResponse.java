package com.backend.dto;

import java.time.Instant;

public record CustomerInteractionResponse(
        String eventId,
        String eventName,
        String marketplaceCode,
        String marketplaceAccountName,
        String screen,
        String entityType,
        String entityExternalId,
        String propertiesJson,
        Instant occurredAt
) {}
