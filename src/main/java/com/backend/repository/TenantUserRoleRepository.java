package com.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.backend.entity.TenantUserRoleEntity;
import com.backend.entity.TenantUserRolePK;

public interface TenantUserRoleRepository extends JpaRepository<TenantUserRoleEntity, TenantUserRolePK> {
    void deleteByTenantUserIdAndRoleId(String tenantUserId, String roleId);
    boolean existsByTenantUserIdAndRoleId(String tenantUserId, String roleId);
}
