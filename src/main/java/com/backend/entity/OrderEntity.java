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
@Table(name = "orders")
public class OrderEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "tenant_id", length = 36)
    private String tenantId;

    @Column(name = "order_code", length = 50, nullable = false)
    private String orderCode;

    @Column(name = "external_order_id", length = 100)
    private String externalOrderId;

    @Column(length = 50)
    private String marketplace;

    @Column(name = "customer_name", nullable = false)
    private String customerName;

    @Column(name = "customer_phone", length = 30)
    private String customerPhone;

    @Column(name = "shipping_address_json", columnDefinition = "JSON")
    private String shippingAddressJson;

    private BigDecimal totalAmount;

    private BigDecimal discountAmount;

    private BigDecimal finalAmount;

    @Column(name = "payment_status", length = 20)
    private String paymentStatus;

    @Column(length = 20)
    private String status;

    private Instant createdAt;

    private Instant updatedAt;

    private Instant deletedAt;
}
