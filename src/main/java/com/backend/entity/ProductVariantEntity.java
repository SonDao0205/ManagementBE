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
@Table(name = "product_variants")
public class ProductVariantEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 36)
    private String tenantId;

    @Column(name = "product_id", nullable = false, length = 36)
    private String productId;

    @Column(name = "variant_code", nullable = false, length = 100)
    private String variantCode;

    @Column(name = "seller_sku", nullable = false, length = 200)
    private String sku;

    @Column(name = "variant_name", length = 255)
    private String variantName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attributes_json", nullable = false, columnDefinition = "jsonb")
    private String attributesJson;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal price;

    @Column(name = "compare_at_price", precision = 18, scale = 2)
    private BigDecimal compareAtPrice;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "stock_on_hand", nullable = false)
    private Integer stockQuantity;

    @Column(name = "reserved_stock", nullable = false)
    private Integer reservedStock;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false)
    private Integer version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
