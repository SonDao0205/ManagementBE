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
@Table(name = "marketplace_accounts")
public class MarketplaceAccountEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 36)
    private String tenantId;

    @Column(name = "marketplace_id", nullable = false, length = 36)
    private String marketplaceId;

    @Column(name = "external_account_id", nullable = false, length = 200)
    private String externalAccountId;

    @Column(name = "shop_cipher")
    private String shopCipher;

    @Column(name = "external_shop_name", nullable = false)
    private String externalShopName;

    @Column(name = "site_id", nullable = false, length = 10)
    private String siteId;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "timezone_name", nullable = false, length = 64)
    private String timezoneName;

    @Column(name = "connection_status", nullable = false, length = 20)
    private String connectionStatus;

    @Column(name = "authorized_at")
    private Instant authorizedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "last_verified_at")
    private Instant lastVerifiedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "settings_json", nullable = false, columnDefinition = "jsonb")
    private String settingsJson;

    @Column(name = "created_by_user_id", length = 36)
    private String createdByUserId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
