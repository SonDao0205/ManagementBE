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
@Table(name = "order_items")
public class OrderItemEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 36)
    private String tenantId;

    @Column(name = "order_id", nullable = false, length = 36)
    private String orderId;

    @Column(name = "product_id", length = 36)
    private String productId;

    @Column(name = "product_variant_id", length = 36)
    private String productVariantId;

    @Column(name = "marketplace_product_id", length = 36)
    private String marketplaceProductId;

    @Column(name = "marketplace_product_variant_id", length = 36)
    private String marketplaceProductVariantId;

    @Column(name = "external_order_item_id", nullable = false, length = 200)
    private String externalOrderItemId;

    @Column(name = "external_product_id", nullable = false, length = 200)
    private String externalProductId;

    @Column(name = "external_sku_id", length = 200)
    private String externalSkuId;

    @Column(name = "seller_sku_snapshot", length = 200)
    private String sku;

    @Column(name = "product_name_snapshot", nullable = false, length = 500)
    private String productName;

    @Column(name = "variant_name_snapshot", length = 255)
    private String variantName;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "unit_price", nullable = false, precision = 18, scale = 2)
    private BigDecimal price;

    @Column(name = "discount_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal discountAmount;

    @Column(name = "paid_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal paidAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "raw_status", nullable = false, length = 100)
    private String rawStatus;

    @Column(name = "canonical_status", nullable = false, length = 30)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", nullable = false, columnDefinition = "jsonb")
    private String rawPayload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
