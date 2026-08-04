package com.backend.dto;

import java.time.Instant;

public record CustomerResponse(
        String id,
        String customerCode,
        String displayName,
        String phoneNumber,
        String email,
        String identityStatus,
        Instant createdAt,
        boolean hasPotentialDuplicates,
        long totalOrders,
        double totalSpend
) {}
