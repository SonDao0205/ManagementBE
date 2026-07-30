package com.backend.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import com.backend.entity.MarketplaceCustomerEntity;

public interface MarketplaceCustomerRepository extends JpaRepository<MarketplaceCustomerEntity, String> {
    Optional<MarketplaceCustomerEntity> findByIdAndTenantId(String id, String tenantId);
}
