package com.backend.dto;

import java.time.Instant;

public record MarketplaceAuthorizationResponse(
        String marketplace,
        String authorizationUrl,
        Instant expiresAt) {
}
