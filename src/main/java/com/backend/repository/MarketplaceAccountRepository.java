package com.backend.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backend.entity.MarketplaceAccountEntity;

public interface MarketplaceAccountRepository
        extends JpaRepository<MarketplaceAccountEntity, String> {

    List<MarketplaceAccountEntity> findByTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            String tenantId);

    Optional<MarketplaceAccountEntity> findByIdAndTenantIdAndDeletedAtIsNull(
            String id,
            String tenantId);

    Optional<MarketplaceAccountEntity>
            findByTenantIdAndMarketplaceIdAndExternalAccountIdAndDeletedAtIsNull(
                    String tenantId,
                    String marketplaceId,
                    String externalAccountId);

    Optional<MarketplaceAccountEntity>
            findByTenantIdAndMarketplaceIdAndExternalAccountId(
                    String tenantId,
                    String marketplaceId,
                    String externalAccountId);

    Optional<MarketplaceAccountEntity> findByMarketplaceIdAndExternalAccountId(
            String marketplaceId,
            String externalAccountId);
}
