package com.backend.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backend.entity.TenantUserEntity;

import jakarta.persistence.LockModeType;

public interface TenantUserRepository extends JpaRepository<TenantUserEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select u from TenantUserEntity u
            join fetch u.tenant t
            join fetch u.credential c
            where lower(u.email) = :email
              and t.deletedAt is null
              and u.deletedAt is null
            """)
    Optional<TenantUserEntity> findForLogin(@Param("email") String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select u from TenantUserEntity u
            join fetch u.tenant t
            join fetch u.credential c
            where u.id = :userId
              and u.deletedAt is null
              and t.deletedAt is null
            """)
    Optional<TenantUserEntity> findForPasswordChange(@Param("userId") String userId);

    @Query(value = """
            SELECT DISTINCT r.role_code
            FROM tenant_user_roles tur
            JOIN roles r
              ON r.id = tur.role_id
             AND r.tenant_scope_key = tur.role_scope_key
            WHERE tur.tenant_user_id = :userId
              AND tur.tenant_id = :tenantId
              AND (r.tenant_id = :tenantId OR r.tenant_id IS NULL)
            ORDER BY r.role_code
            """, nativeQuery = true)
    List<String> findRoleCodes(
            @Param("userId") String userId,
            @Param("tenantId") String tenantId);

    @Query(value = """
            SELECT DISTINCT p.permission_code
            FROM tenant_user_roles tur
            JOIN roles r
              ON r.id = tur.role_id
             AND r.tenant_scope_key = tur.role_scope_key
            JOIN role_permissions rp ON rp.role_id = r.id
            JOIN permissions p ON p.id = rp.permission_id
            WHERE tur.tenant_user_id = :userId
              AND tur.tenant_id = :tenantId
              AND (r.tenant_id = :tenantId OR r.tenant_id IS NULL)
            ORDER BY p.permission_code
            """, nativeQuery = true)
    List<String> findPermissionCodes(
            @Param("userId") String userId,
            @Param("tenantId") String tenantId);
}
