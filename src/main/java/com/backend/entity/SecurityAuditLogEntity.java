package com.backend.entity;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "security_audit_logs")
public class SecurityAuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", length = 36)
    private String tenantId;

    @Column(name = "actor_type", nullable = false, length = 20)
    private String actorType;

    @Column(name = "actor_id", length = 36)
    private String actorId;

    @Column(name = "action_code", nullable = false, length = 100)
    private String actionCode;

    @Column(name = "target_type", length = 50)
    private String targetType;

    @Column(name = "target_id", length = 200)
    private String targetId;

    @Column(nullable = false, length = 20)
    private String result;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", nullable = false, columnDefinition = "json")
    private String metadataJson = "{}";

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}
