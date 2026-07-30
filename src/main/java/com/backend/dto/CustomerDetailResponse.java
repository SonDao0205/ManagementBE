package com.backend.dto;

import java.time.Instant;
import java.util.List;

public record CustomerDetailResponse(
        String id,
        String customerCode,
        String displayName,
        String phoneNumber,
        String email,
        String identityStatus,
        Instant createdAt,
        Instant updatedAt,
        String mergedIntoId,
        List<LinkedChannelDto> linkedChannels,
        CustomerMetricsDto metrics
) {}
