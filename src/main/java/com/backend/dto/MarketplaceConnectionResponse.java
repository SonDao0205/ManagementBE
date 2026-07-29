package com.backend.dto;

import java.time.Instant;
import java.util.List;

public record MarketplaceConnectionResponse(
        String id,
        String marketplace,
        String marketplaceName,
        String externalAccountId,
        String shopName,
        String siteId,
        String currency,
        String timezoneName,
        String status,
        Instant authorizedAt,
        Instant tokenExpiresAt,
        Instant lastVerifiedAt,
        List<String> scopes) {
}
