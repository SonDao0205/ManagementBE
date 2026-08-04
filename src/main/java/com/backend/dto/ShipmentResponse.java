package com.backend.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record ShipmentResponse(
        String id,
        String tenantId,
        String orderId,
        String orderCode,
        String externalPackageId,
        String waybillCode,
        String carrierName,
        String destination,
        BigDecimal codAmount,
        String latestMilestone,
        String milestoneType,
        String status,
        Instant readyToShipAt,
        Instant shippedAt,
        Instant deliveredAt,
        Instant createdAt,
        Instant updatedAt) {
}
