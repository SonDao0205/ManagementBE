package com.backend.dto;

import com.backend.entity.ShipmentEntity;

import java.math.BigDecimal;
import java.time.Instant;

public record ShipmentResponse(
        String id,
        String tenantId,
        String orderId,
        String waybillCode,
        String carrierName,
        String destination,
        BigDecimal codAmount,
        String latestMilestone,
        String milestoneType,
        Instant shippedAt,
        Instant deliveredAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static ShipmentResponse from(ShipmentEntity entity) {
        return new ShipmentResponse(
                entity.getId(),
                entity.getTenantId(),
                entity.getOrderId(),
                entity.getWaybillCode(),
                entity.getCarrierName(),
                entity.getDestination(),
                entity.getCodAmount(),
                entity.getLatestMilestone(),
                entity.getMilestoneType(),
                entity.getShippedAt(),
                entity.getDeliveredAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
