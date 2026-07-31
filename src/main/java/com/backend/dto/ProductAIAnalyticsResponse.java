package com.backend.dto;

import java.math.BigDecimal;
import java.util.List;

public record ProductAIAnalyticsResponse(
        List<ProductPerformance> topProducts,
        List<ProductPerformance> bottomProducts,
        int totalOrders,
        int aiClosed,
        int hybridClosed,
        int humanClosed,
        double conversionRate,
        double responseTime,
        double csat,
        BigDecimal costSaved,
        List<FunnelStage> funnelStages
) {
    public record ProductPerformance(
            String name,
            String sku,
            int sold,
            BigDecimal revenue,
            int stock,
            String status,
            String img,
            String channel
    ) {}

    public record FunnelStage(
            String stageName,
            int value,
            String valueLabel,
            double percentage
    ) {}
}
