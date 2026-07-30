package com.backend.entity;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@IdClass(TenantUserRolePK.class)
@Table(name = "tenant_user_roles")
public class TenantUserRoleEntity {

    @Id
    @Column(name = "tenant_user_id", length = 36, nullable = false)
    private String tenantUserId;

    @Id
    @Column(name = "role_id", length = 36, nullable = false)
    private String roleId;

    @Column(name = "tenant_id", length = 36, nullable = false)
    private String tenantId;

    @Column(name = "role_scope_key", length = 36, nullable = false)
    private String roleScopeKey;

    @Column(name = "assigned_by_user_id", length = 36)
    private String assignedByUserId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
