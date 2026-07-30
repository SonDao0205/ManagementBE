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
@Table(name = "marketplace_products")
public class MarketplaceProductEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 36)
    private String tenantId;

    @Column(name = "product_id", nullable = false, length = 36)
    private String productId;

    @Column(name = "marketplace_account_id", nullable = false, length = 36)
    private String marketplaceAccountId;

    @Column(name = "external_product_id", nullable = false, length = 200)
    private String externalProductId;

    @Column(name = "external_category_id", length = 200)
    private String externalCategoryId;

    @Column(name = "external_title", length = 500)
    private String externalTitle;

    @Column(name = "raw_status", nullable = false, length = 100)
    private String rawStatus;

    @Column(name = "canonical_status", nullable = false, length = 30)
    private String canonicalStatus;

    @Column(name = "sync_status", nullable = false, length = 20)
    private String syncStatus;

    @Column(name = "external_version", length = 100)
    private String externalVersion;

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
