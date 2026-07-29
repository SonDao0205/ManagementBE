package com.backend.entity;

import java.time.Instant;

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
@Table(name = "tenant_password_history")
public class TenantPasswordHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_user_id", nullable = false, length = 36)
    private String tenantUserId;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "password_algorithm", nullable = false, length = 30)
    private String passwordAlgorithm;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    @Column(name = "changed_by_type", nullable = false, length = 20)
    private String changedByType;
}
