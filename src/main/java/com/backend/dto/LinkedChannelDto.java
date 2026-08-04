package com.backend.dto;

import java.time.Instant;

public record LinkedChannelDto(
        String linkId,
        String marketplaceCustomerId,
        String channelName,
        String accountName,
        String buyerName,
        String avatarUrl,
        String phoneMasked,
        String emailMasked,
        String verificationStatus,
        Instant linkedAt
) {}
