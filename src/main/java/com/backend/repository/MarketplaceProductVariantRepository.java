package com.backend.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backend.entity.MarketplaceProductVariantEntity;

public interface MarketplaceProductVariantRepository
        extends JpaRepository<MarketplaceProductVariantEntity, String> {

    Optional<MarketplaceProductVariantEntity>
            findByMarketplaceProductIdAndProductVariantIdAndDeletedAtIsNull(
                    String marketplaceProductId,
                    String productVariantId);

    Optional<MarketplaceProductVariantEntity>
            findByMarketplaceProductIdAndExternalSkuIdAndDeletedAtIsNull(
                    String marketplaceProductId,
                    String externalSkuId);
}
