package com.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.backend.entity.TenantUserCredentialEntity;

public interface TenantUserCredentialRepository extends JpaRepository<TenantUserCredentialEntity, String> {
}
