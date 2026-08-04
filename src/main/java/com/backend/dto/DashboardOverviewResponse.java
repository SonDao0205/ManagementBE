package com.backend.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record DashboardOverviewResponse(
        BigDecimal todayRevenue,
        long newOrdersToday,
        long newCustomersToday,
        List<ChannelProductCount> channels,
        List<RecentOrder> recentOrders) {

    public record ChannelProductCount(
            String marketplace,
            String marketplaceName,
            long productCount) {
    }

    public record RecentOrder(
            String id,
            String externalOrderId,
            String customerName,
            String marketplace,
            String marketplaceName,
            BigDecimal totalAmount,
            String status,
            Instant createdAt) {
    }
}
