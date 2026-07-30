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
@Table(name = "marketplace_product_variants")
public class MarketplaceProductVariantEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 36)
    private String tenantId;

    @Column(name = "marketplace_product_id", nullable = false, length = 36)
    private String marketplaceProductId;

    @Column(name = "product_variant_id", nullable = false, length = 36)
    private String productVariantId;

    @Column(name = "external_sku_id", nullable = false, length = 200)
    private String externalSkuId;

    @Column(name = "external_seller_sku", length = 200)
    private String externalSellerSku;

    @Column(name = "external_price")
    private BigDecimal externalPrice;

    @Column(name = "external_stock")
    private Integer externalStock;

    @Column(name = "raw_status", nullable = false, length = 100)
    private String rawStatus;

    @Column(name = "canonical_status", nullable = false, length = 30)
    private String canonicalStatus;

    @Column(name = "sync_status", nullable = false, length = 20)
    private String syncStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", nullable = false, columnDefinition = "json")
    private String rawPayload;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
