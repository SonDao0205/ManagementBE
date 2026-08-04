package com.backend.entity;

import java.math.BigDecimal;
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
@Table(name = "orders")
public class OrderEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 36)
    private String tenantId;

    @Column(name = "marketplace_account_id", nullable = false, length = 36)
    private String marketplaceAccountId;

    @Column(name = "marketplace_customer_id", length = 36)
    private String marketplaceCustomerId;

    @Column(name = "external_order_id", nullable = false, length = 200)
    private String externalOrderId;

    @Column(name = "raw_status", nullable = false, length = 100)
    private String rawStatus;

    @Column(name = "canonical_status", nullable = false, length = 30)
    private String status;

    @Column(name = "payment_status", nullable = false, length = 30)
    private String paymentStatus;

    @Column(name = "refund_status", nullable = false, length = 30)
    private String refundStatus;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "subtotal_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal subtotalAmount;

    @Column(name = "shipping_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal shippingAmount;

    @Column(name = "discount_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal discountAmount;

    @Column(name = "tax_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal taxAmount;

    @Column(name = "total_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalAmount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "shipping_address_json", nullable = false, columnDefinition = "jsonb")
    private String shippingAddressJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "billing_address_json", nullable = false, columnDefinition = "jsonb")
    private String billingAddressJson;

    @Column(name = "shipping_address_encrypted", columnDefinition = "TEXT")
    private String shippingAddressEncrypted;

    @Column(name = "billing_address_encrypted", columnDefinition = "TEXT")
    private String billingAddressEncrypted;

    @Column(name = "pii_key_version", length = 30)
    private String piiKeyVersion;

    @Column(name = "buyer_note", columnDefinition = "TEXT")
    private String buyerNote;

    @Column(name = "internal_note", columnDefinition = "TEXT")
    private String internalNote;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", nullable = false, columnDefinition = "jsonb")
    private String rawPayload;

    @Column(name = "external_created_at", nullable = false)
    private Instant externalCreatedAt;

    @Column(name = "external_updated_at", nullable = false)
    private Instant externalUpdatedAt;

    @Column(name = "last_synced_at", nullable = false)
    private Instant lastSyncedAt;

    @Column(nullable = false)
    private Integer version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
