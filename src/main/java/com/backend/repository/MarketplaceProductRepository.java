package com.backend.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backend.entity.MarketplaceProductEntity;

public interface MarketplaceProductRepository
        extends JpaRepository<MarketplaceProductEntity, String> {

    Optional<MarketplaceProductEntity>
            findByProductIdAndMarketplaceAccountIdAndDeletedAtIsNull(
                    String productId,
                    String marketplaceAccountId);

    Optional<MarketplaceProductEntity>
            findByMarketplaceAccountIdAndExternalProductIdAndDeletedAtIsNull(
                    String marketplaceAccountId,
                    String externalProductId);

    List<MarketplaceProductEntity>
            findByTenantIdAndProductIdAndDeletedAtIsNull(
                    String tenantId,
                    String productId);
}
