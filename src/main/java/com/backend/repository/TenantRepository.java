package com.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.backend.entity.TenantEntity;

public interface TenantRepository extends JpaRepository<TenantEntity, String> {
}
