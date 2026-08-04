package com.backend.entity;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "shipments")
public class ShipmentEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 36)
    private String tenantId;

    @Column(name = "order_id", nullable = false, length = 36)
    private String orderId;

    @Column(name = "external_package_id", nullable = false, length = 200)
    private String externalPackageId;

    @Column(name = "tracking_number", length = 200)
    private String trackingNumber;

    @Column(name = "shipping_provider", length = 150)
    private String shippingProvider;

    @Column(name = "raw_status", nullable = false, length = 100)
    private String rawStatus;

    @Column(name = "canonical_status", nullable = false, length = 30)
    private String status;

    @Column(name = "shipping_label_url", columnDefinition = "TEXT")
    private String shippingLabelUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "package_items_json", nullable = false, columnDefinition = "jsonb")
    private String packageItemsJson;

    @Column(name = "ready_to_ship_at")
    private Instant readyToShipAt;

    @Column(name = "shipped_at")
    private Instant shippedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", nullable = false, columnDefinition = "jsonb")
    private String rawPayload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
