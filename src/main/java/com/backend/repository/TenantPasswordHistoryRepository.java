package com.backend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backend.entity.TenantPasswordHistoryEntity;

public interface TenantPasswordHistoryRepository
        extends JpaRepository<TenantPasswordHistoryEntity, Long> {

    List<TenantPasswordHistoryEntity> findTop5ByTenantUserIdOrderByChangedAtDesc(
            String tenantUserId);
}
