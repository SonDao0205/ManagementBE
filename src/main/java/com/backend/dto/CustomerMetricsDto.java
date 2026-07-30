package com.backend.dto;

public record CustomerMetricsDto(
        long totalOrders,
        double totalSpend,
        String lastChannelSeen
) {}
