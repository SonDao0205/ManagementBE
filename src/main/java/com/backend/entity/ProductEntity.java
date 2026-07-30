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
@Table(name = "products")
public class ProductEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 36)
    private String tenantId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "product_code", length = 100)
    private String productCode;

    @Column(length = 100)
    private String category;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(precision = 15, scale = 2)
    private BigDecimal price;

    @Column(name = "cost_price", precision = 15, scale = 2)
    private BigDecimal costPrice;

    @Column(name = "total_stock")
    private Integer totalStock;

    @Column(name = "min_stock_alert")
    private Integer minStockAlert;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(length = 20)
    private String status;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
