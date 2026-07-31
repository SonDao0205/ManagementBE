package com.backend.dto;

import java.math.BigDecimal;
import java.util.List;

public record RevenueAnalyticsResponse(
        BigDecimal revenue,
        BigDecimal cost,
        BigDecimal shipping,
        BigDecimal fee,
        BigDecimal profit,
        double margin,
        double growth,
        List<ChartPoint> chartPoints,
        List<FinancialRow> rows,
        String aiInsightTitle,
        String aiInsightDesc
) {
    public record ChartPoint(
            String month,
            BigDecimal revenue,
            BigDecimal profit
    ) {}

    public record FinancialRow(
            String month,
            BigDecimal revenue,
            BigDecimal cost,
            BigDecimal shipping,
            BigDecimal fee,
            BigDecimal profit,
            double margin,
            double growth
    ) {}
}
