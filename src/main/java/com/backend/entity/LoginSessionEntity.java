package com.backend.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "login_sessions")
public class LoginSessionEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "actor_type", nullable = false, length = 20)
    private String actorType;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_user_id")
    private TenantUserEntity tenantUser;

    @Column(name = "session_token_hash", nullable = false, length = 64)
    private String sessionTokenHash;

    @Column(name = "auth_stage", nullable = false, length = 30)
    private String authStage;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;
}
