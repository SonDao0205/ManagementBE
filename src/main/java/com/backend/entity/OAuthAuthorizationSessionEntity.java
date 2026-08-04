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
@Table(name = "oauth_authorization_sessions")
public class OAuthAuthorizationSessionEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 36)
    private String tenantId;

    @Column(name = "tenant_user_id", nullable = false, length = 36)
    private String tenantUserId;

    @Column(name = "marketplace_id", nullable = false, length = 36)
    private String marketplaceId;

    @Column(name = "state_hash", nullable = false, length = 64)
    private String stateHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "requested_scopes_json", nullable = false, columnDefinition = "jsonb")
    private String requestedScopesJson;

    @Column(name = "return_url", length = 500)
    private String returnUrl;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
