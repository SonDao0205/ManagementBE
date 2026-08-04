package com.backend.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record RevenueAnalyticsResponse(
        BigDecimal totalRevenue,
        BigDecimal netProfit,
        BigDecimal totalCost,
        BigDecimal profitMargin,
        BigDecimal growthPercent,
        Instant periodStart,
        Instant periodEnd,
        String chartGranularity,
        List<RevenueChartPoint> chartPoints) {

    public record RevenueChartPoint(
            Instant periodStart,
            String label,
            BigDecimal revenue) {
    }
}
