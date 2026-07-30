package com.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "shipments")
public class ShipmentEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "tenant_id", length = 36)
    private String tenantId;

    @Column(name = "order_id", length = 36)
    private String orderId;

    @Column(name = "waybill_code", length = 100)
    private String waybillCode;

    @Column(name = "carrier_name", length = 50)
    private String carrierName;

    @Column(length = 255)
    private String destination;

    @Column(name = "cod_amount")
    private BigDecimal codAmount;

    @Column(name = "latest_milestone", length = 255)
    private String latestMilestone;

    @Column(name = "milestone_type", length = 30)
    private String milestoneType;

    @Column(name = "shipped_at")
    private Instant shippedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
