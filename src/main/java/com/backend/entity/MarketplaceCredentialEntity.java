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
@Table(name = "marketplace_credentials")
public class MarketplaceCredentialEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "marketplace_account_id", nullable = false, length = 36)
    private String marketplaceAccountId;

    @Column(name = "app_key", length = 150)
    private String appKey;

    @Column(name = "access_token_encrypted", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String accessTokenEncrypted;

    @Column(name = "refresh_token_encrypted", columnDefinition = "MEDIUMTEXT")
    private String refreshTokenEncrypted;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scopes_json", nullable = false, columnDefinition = "json")
    private String scopesJson;

    @Column(name = "encryption_key_version", nullable = false, length = 30)
    private String encryptionKeyVersion;

    @Column(name = "access_token_expires_at")
    private Instant accessTokenExpiresAt;

    @Column(name = "refresh_token_expires_at")
    private Instant refreshTokenExpiresAt;

    @Column(name = "last_refreshed_at")
    private Instant lastRefreshedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
