package com.backend.entity;

import java.math.BigDecimal;
import java.time.Instant;

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

    @Column(name = "product_id", nullable = false, length = 36)
    private String productId;

    @Column(name = "tenant_id", nullable = false, length = 36)
    private String tenantId;

    @Column(nullable = false, length = 100)
    private String sku;

    @Column(name = "variant_name", length = 100)
    private String variantName;

    @Column(precision = 15, scale = 2)
    private BigDecimal price;

    @Column(name = "stock_quantity")
    private Integer stockQuantity;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
