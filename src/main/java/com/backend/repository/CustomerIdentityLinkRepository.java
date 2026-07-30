package com.backend.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import com.backend.entity.CustomerIdentityLinkEntity;

public interface CustomerIdentityLinkRepository extends JpaRepository<CustomerIdentityLinkEntity, String> {
    List<CustomerIdentityLinkEntity> findByCustomerIdAndTenantId(String customerId, String tenantId);
    List<CustomerIdentityLinkEntity> findByCustomerId(String customerId);
    Optional<CustomerIdentityLinkEntity> findByMarketplaceCustomerId(String marketplaceCustomerId);
}
